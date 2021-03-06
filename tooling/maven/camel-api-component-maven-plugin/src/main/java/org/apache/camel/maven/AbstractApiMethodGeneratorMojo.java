/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.maven;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.camel.support.component.ApiMethodArg;
import org.apache.camel.support.component.ApiMethodParser;
import org.apache.camel.support.component.ArgumentSubstitutionParser;
import org.apache.camel.util.StringHelper;
import org.apache.commons.lang.ClassUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.velocity.VelocityContext;

/**
 * Base Mojo class for ApiMethod generators.
 */
public abstract class AbstractApiMethodGeneratorMojo extends AbstractApiMethodBaseMojo {

    private static final Map<Class<?>, String> PRIMITIVE_VALUES;

    @Parameter(required = true, property = PREFIX + "proxyClass")
    protected String proxyClass;

    @Parameter
    protected String classPrefix;

    @Parameter
    protected String apiName;

    /**
     * Method alias patterns for all APIs.
     */
    @Parameter
    protected List<ApiMethodAlias> aliases = Collections.emptyList();

    // cached fields
    private Class<?> proxyType;

    private Pattern propertyNamePattern;
    private Pattern propertyTypePattern;

    @Override
    public void executeInternal() throws MojoExecutionException {

        setCompileSourceRoots();

        // load proxy class and get enumeration file to generate
        final Class proxyType = getProxyType();

        // parse pattern for excluded endpoint properties
        if (excludeConfigNames != null) {
            propertyNamePattern = Pattern.compile(excludeConfigNames);
        }
        if (excludeConfigTypes != null) {
            propertyTypePattern = Pattern.compile(excludeConfigTypes);
        }

        // create parser
        ApiMethodParser parser = createAdapterParser(proxyType);

        List<String> signatures = new ArrayList<>();
        Map<String, Map<String, String>> parameters = new HashMap<>();
        List<SignatureModel> data = getSignatureList();
        for (SignatureModel model : data) {
            signatures.add(model.getSignature());
            String method = StringHelper.before(model.getSignature(), "(");
            if (method != null && method.contains(" ")) {
                method = StringHelper.after(method, " ");
            }
            if (method != null) {
                parameters.put(method, model.getParameters());
            }
        }
        parser.setSignatures(signatures);
        parser.setParameters(parameters);

        parser.setClassLoader(getProjectClassLoader());

        // parse signatures
        @SuppressWarnings("unchecked")
        final List<ApiMethodParser.ApiMethodModel> models = parser.parse();

        // generate enumeration from model
        mergeTemplate(getApiMethodContext(models), getApiMethodFile(), "/api-method-enum.vm");

        // generate EndpointConfiguration for this Api
        mergeTemplate(getEndpointContext(models), getConfigurationFile(), "/api-endpoint-config.vm");

        // generate junit test if it doesn't already exist under test source directory
        // i.e. it may have been generated then moved there and populated with test values
        final String testFilePath = getTestFilePath();
        if (!new File(project.getBuild().getTestSourceDirectory(), testFilePath).exists()) {
            mergeTemplate(getApiTestContext(models), new File(generatedTestDir, testFilePath), "/api-route-test.vm");
        }
    }

    @SuppressWarnings("unchecked")
    protected ApiMethodParser createAdapterParser(Class proxyType) {
        return new ArgumentSubstitutionParser(proxyType, getArgumentSubstitutions());
    }

    public abstract List<SignatureModel> getSignatureList() throws MojoExecutionException;

    public Class<?> getProxyType() throws MojoExecutionException {
        if (proxyType == null) {
            // load proxy class from Project runtime dependencies
            try {
                proxyType = getProjectClassLoader().loadClass(proxyClass);
            } catch (ClassNotFoundException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
        }
        return proxyType;
    }

    private VelocityContext getApiMethodContext(List<ApiMethodParser.ApiMethodModel> models) throws MojoExecutionException {
        VelocityContext context = getCommonContext(models);
        context.put("enumName", getEnumName());
        return context;
    }

    public File getApiMethodFile() throws MojoExecutionException {
        final StringBuilder fileName = new StringBuilder();
        fileName.append(outPackage.replace(".", Matcher.quoteReplacement(File.separator))).append(File.separator);
        fileName.append(getEnumName()).append(".java");
        return new File(generatedSrcDir, fileName.toString());
    }

    private String getEnumName() throws MojoExecutionException {
        String proxyClassWithCanonicalName = getProxyClassWithCanonicalName(proxyClass);
        String prefix = classPrefix != null ? classPrefix : "";
        return prefix + proxyClassWithCanonicalName.substring(proxyClassWithCanonicalName.lastIndexOf('.') + 1) + "ApiMethod";
    }

    private VelocityContext getApiTestContext(List<ApiMethodParser.ApiMethodModel> models) throws MojoExecutionException {
        VelocityContext context = getCommonContext(models);
        context.put("testName", getUnitTestName());
        context.put("scheme", scheme);
        context.put("componentPackage", componentPackage);
        context.put("componentName", componentName);
        context.put("enumName", getEnumName());
        return context;
    }

    private String getTestFilePath() throws MojoExecutionException {
        final StringBuilder fileName = new StringBuilder();
        fileName.append(componentPackage.replace(".", Matcher.quoteReplacement(File.separator))).append(File.separator);
        fileName.append(getUnitTestName()).append(".java");
        return fileName.toString();
    }

    private String getUnitTestName() throws MojoExecutionException {
        String proxyClassWithCanonicalName = getProxyClassWithCanonicalName(proxyClass);
        String prefix = classPrefix != null ? classPrefix : "";
        return prefix + proxyClassWithCanonicalName.substring(proxyClassWithCanonicalName.lastIndexOf('.') + 1)
               + "IntegrationTest";
    }

    private VelocityContext getEndpointContext(List<ApiMethodParser.ApiMethodModel> models) throws MojoExecutionException {
        VelocityContext context = getCommonContext(models);
        context.put("apiName", apiName);

        // TODO: we should include alias information as well

        String apiMethodNames = models.stream().map(ApiMethodParser.ApiMethodModel::getName)
                .distinct()
                .sorted()
                .collect(Collectors.joining(","));
        context.put("apiMethods", apiMethodNames);
        context.put("configName", getConfigName());
        context.put("componentName", componentName);
        context.put("componentPackage", componentPackage);

        // generate parameter names and types for configuration, sorted by parameter name
        Map<String, ApiMethodArg> parameters = new TreeMap<>();
        for (ApiMethodParser.ApiMethodModel model : models) {
            for (ApiMethodArg argument : model.getArguments()) {
                final String name = argument.getName();
                final Class<?> type = argument.getType();
                final String typeName = type.getCanonicalName();
                if (!parameters.containsKey(name)
                        && (propertyNamePattern == null || !propertyNamePattern.matcher(name).matches())
                        && (propertyTypePattern == null || !propertyTypePattern.matcher(typeName).matches())) {
                    parameters.put(name, argument);
                }
            }
        }

        // add custom parameters
        if (extraOptions != null && extraOptions.length > 0) {
            for (ExtraOption option : extraOptions) {
                final String name = option.getName();
                final String argWithTypes = option.getType().replace(" ", "");
                final int rawEnd = argWithTypes.indexOf('<');
                String typeArgs = null;
                Class<?> argType;
                try {
                    if (rawEnd != -1) {
                        argType = getProjectClassLoader().loadClass(argWithTypes.substring(0, rawEnd));
                        typeArgs = argWithTypes.substring(rawEnd + 1, argWithTypes.lastIndexOf('>'));
                    } else {
                        argType = getProjectClassLoader().loadClass(argWithTypes);
                    }
                } catch (ClassNotFoundException e) {
                    throw new MojoExecutionException(
                            String.format("Error loading extra option [%s %s] : %s",
                                    argWithTypes, name, e.getMessage()),
                            e);
                }
                parameters.put(name, new ApiMethodArg(name, argType, typeArgs, option.getDescription()));
            }
        }

        context.put("parameters", parameters);
        return context;
    }

    private String replaceAlias(String name) {
        for (ApiMethodAlias alias : aliases) {
            if (name.matches(alias.getMethodPattern())) {
                return alias.getMethodAlias();
            }
        }
        return name;
    }

    private File getConfigurationFile() throws MojoExecutionException {
        final StringBuilder fileName = new StringBuilder();
        // endpoint configuration goes in component package
        fileName.append(componentPackage.replace(".", Matcher.quoteReplacement(File.separator))).append(File.separator);
        fileName.append(getConfigName()).append(".java");
        return new File(generatedSrcDir, fileName.toString());
    }

    private String getConfigName() throws MojoExecutionException {
        String proxyClassWithCanonicalName = getProxyClassWithCanonicalName(proxyClass);
        String prefix = classPrefix != null ? classPrefix : "";
        return prefix + proxyClassWithCanonicalName.substring(proxyClassWithCanonicalName.lastIndexOf('.') + 1)
               + "EndpointConfiguration";
    }

    private String getProxyClassWithCanonicalName(String proxyClass) {
        return proxyClass.replace("$", "");
    }

    private VelocityContext getCommonContext(List<ApiMethodParser.ApiMethodModel> models) throws MojoExecutionException {
        VelocityContext context = new VelocityContext();
        context.put("models", models);
        context.put("proxyType", getProxyType());
        context.put("helper", this);
        return context;
    }

    public ArgumentSubstitutionParser.Substitution[] getArgumentSubstitutions() {
        ArgumentSubstitutionParser.Substitution[] subs = new ArgumentSubstitutionParser.Substitution[substitutions.length];
        for (int i = 0; i < substitutions.length; i++) {
            final Substitution substitution = substitutions[i];
            subs[i] = new ArgumentSubstitutionParser.Substitution(
                    substitution.getMethod(),
                    substitution.getArgName(), substitution.getArgType(),
                    substitution.getReplacement(), substitution.isReplaceWithType());
        }
        return subs;
    }

    public static String getType(Class<?> clazz) {
        if (clazz.isArray()) {
            // create a zero length array and get the class from the instance
            return "new " + getCanonicalName(clazz).replaceAll("\\[\\]", "[0]") + ".getClass()";
        } else {
            return getCanonicalName(clazz) + ".class";
        }
    }

    public static String getTestName(ApiMethodParser.ApiMethodModel model) {
        final StringBuilder builder = new StringBuilder();
        final String name = model.getMethod().getName();
        builder.append(Character.toUpperCase(name.charAt(0)));
        builder.append(name, 1, name.length());
        // find overloaded method suffix from unique name
        final String uniqueName = model.getUniqueName();
        if (uniqueName.length() > name.length()) {
            builder.append(uniqueName, name.length(), uniqueName.length());
        }
        return builder.toString();
    }

    public static boolean isVoidType(Class<?> resultType) {
        return resultType == Void.TYPE;
    }

    public String getExchangePropertyPrefix() {
        // exchange property prefix
        return "Camel" + componentName + ".";
    }

    public static String getResultDeclaration(Class<?> resultType) {
        if (resultType.isPrimitive()) {
            return ClassUtils.primitiveToWrapper(resultType).getSimpleName();
        } else {
            return getCanonicalName(resultType);
        }
    }

    static {
        PRIMITIVE_VALUES = new HashMap<>();
        PRIMITIVE_VALUES.put(Boolean.TYPE, "Boolean.FALSE");
        PRIMITIVE_VALUES.put(Byte.TYPE, "(byte) 0");
        PRIMITIVE_VALUES.put(Character.TYPE, "(char) 0");
        PRIMITIVE_VALUES.put(Short.TYPE, "(short) 0");
        PRIMITIVE_VALUES.put(Integer.TYPE, "0");
        PRIMITIVE_VALUES.put(Long.TYPE, "0L");
        PRIMITIVE_VALUES.put(Float.TYPE, "0.0f");
        PRIMITIVE_VALUES.put(Double.TYPE, "0.0d");
    }

    public boolean hasDoc(ApiMethodArg argument) {
        return argument.getDescription() != null && !argument.getDescription().isEmpty();
    }

    public String getDoc(ApiMethodArg argument) {
        if (argument.getDescription() == null || argument.getDescription().isEmpty()) {
            return "";
        }
        return argument.getDescription();
    }

    public String getApiName(String apiName) {
        if (apiName == null || apiName.isEmpty()) {
            return "DEFAULT";
        }
        return apiName;
    }

    public static String getDefaultArgValue(Class<?> aClass) {
        if (aClass.isPrimitive()) {
            // lookup default primitive value string
            return PRIMITIVE_VALUES.get(aClass);
        } else {
            // return type cast null string
            return "null";
        }
    }

    public static String getBeanPropertySuffix(String parameter) {
        // capitalize first character
        StringBuilder builder = new StringBuilder();
        builder.append(Character.toUpperCase(parameter.charAt(0)));
        builder.append(parameter, 1, parameter.length());
        return builder.toString();
    }

    public String getCanonicalName(ApiMethodArg argument) throws MojoExecutionException {

        // replace primitives with wrapper classes
        final Class<?> type = argument.getType();
        if (type.isPrimitive()) {
            return getCanonicalName(ClassUtils.primitiveToWrapper(type));
        }

        // get default name prefix
        String canonicalName = getCanonicalName(type);

        final String typeArgs = argument.getTypeArgs();
        if (typeArgs != null) {

            // add generic type arguments
            StringBuilder parameterizedType = new StringBuilder(canonicalName);
            parameterizedType.append('<');

            // Note: its ok to split, since we don't support parsing nested type arguments
            final String[] argTypes = typeArgs.split(",");
            final int nTypes = argTypes.length;
            int i = 0;
            for (String argType : argTypes) {

                // javadoc sometimes contains zero-width spaces
                if (argType.charAt(0) == '\u200b') {
                    argType = argType.substring(1);
                }

                if ("URL".equals(argType)) {
                    parameterizedType.append("java.net.URL");
                } else if ("URI".equals(argType)) {
                    parameterizedType.append("java.net.URI");
                } else {
                    // try loading as is first
                    try {
                        parameterizedType.append(getCanonicalName(getProjectClassLoader().loadClass(argType)));
                    } catch (ClassNotFoundException e) {
                        // try loading with default java.lang package prefix
                        try {
                            if (log.isDebugEnabled()) {
                                log.debug("Could not load " + argType + ", trying to load java.lang." + argType);
                            }
                            parameterizedType.append(
                                    getCanonicalName(getProjectClassLoader().loadClass("java.lang." + argType)));
                        } catch (ClassNotFoundException e1) {
                            parameterizedType.append("?");
                            // if the length of the artType is 1, we think that it's variable type parameter (like T in List<T>)
                            // not perfect solution, but should work in most of the cases
                            if (argType.trim().length() > 1) {
                                log.warn("Ignoring type parameters <" + typeArgs + "> for argument " + argument.getName()
                                         + ", unable to load parametric type argument " + argType,
                                        e1);
                            }
                        }
                    }
                }

                if (++i < nTypes) {
                    parameterizedType.append(",");
                }
            }

            parameterizedType.append('>');
            canonicalName = parameterizedType.toString();
        }

        return canonicalName;
    }
}
