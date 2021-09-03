package org.reflections;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class LambdaUsageHandler {

    private static final String LAMBDA_REF = ".lambda$";
    private static final Pattern LAMBDA_REF_PATTERN = Pattern.compile("^(.*?)\\.lambda\\$(.*?)\\$\\d+\\(.*?\\)");

    public boolean isLambda(String name) {
        return name.contains(LAMBDA_REF);
    }

    public String findRefSubstitution(String lambdaRefValue,
        Class<?> scannerClass, Store store, ClassLoader[] classLoaders) {
        return findRefSubstitution(lambdaRefValue, scannerClass, store, classLoaders, new HashSet<>());
    }

    private String findRefSubstitution(String lambdaRefValue, Class<?> scannerClass,
        Store store, ClassLoader[] classLoaders, Set<String> visited) {
        if (visited.contains(lambdaRefValue)) {
            return lambdaRefValue;
        }
        visited.add(lambdaRefValue);

        // give lambda ref second chance
        String className = className(lambdaRefValue);
        String refName = refName(lambdaRefValue);

        String classAndRefName = String.format("%s.%s", className, refName);

        List<String> classVals = store.findValuesByFilter(scannerClass,
            key -> key.contains(className)).stream()
            .collect(Collectors.toList());

        List<String> classAndRefsVals = classVals.stream()
            .filter(key -> key.contains(classAndRefName))
            .collect(Collectors.toList());

        if (!classAndRefsVals.isEmpty()) {
            return classAndRefsVals.get(0);
        }

        // no give up, lets un-reference references
        String subLambdaRef = String.format("%s%s$", LAMBDA_REF, refName);
        List<String> subLambdaRefValues = classVals.stream()
            .filter(key -> key.contains(subLambdaRef))
            .collect(Collectors.toList());
        for (String subLambdaRefValue : subLambdaRefValues) {
            String resolvedRefValue = findRefSubstitution(
                subLambdaRefValue, scannerClass, store, classLoaders, visited);
            if (!isLambda(resolvedRefValue)) {
                return resolvedRefValue;
            }
        }

        // no give up, fuzzy search better than exception
        String memberDefinition = getMemberDefinition(className, refName, classLoaders);
        if (memberDefinition.isBlank()) {
            // didn't find any possible substitution...
            return lambdaRefValue;
        }

        return memberDefinition;
    }

    private String getMemberDefinition(String className, String refName, ClassLoader[] classLoaders) {
        Class<?> aClass = ReflectionUtils.forName(className, classLoaders);
        Set<Method> matchedMethods = ReflectionUtils
            .getAllMethods(aClass, s -> s.getName().equals(refName)).stream()
            .filter(m -> !Modifier.isAbstract(m.getModifiers()))
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Constructor> matchedConstructors = ReflectionUtils
            .getAllConstructors(aClass, s -> s.getName().equals(refName));
        Set<Field> matchedFields = ReflectionUtils
            .getAllFields(aClass, s -> s.getName().equals(refName));

        if (!matchedMethods.isEmpty()) {
            return getMethodsDefinition(matchedMethods);
        }
        if (!matchedConstructors.isEmpty()) {
            return getConstructorDefinition(matchedConstructors);
        }
        if (!matchedFields.isEmpty()) {
            return getFieldDefinition(matchedFields);
        }

        return "";
    }

    private String getMethodsDefinition(Set<Method> methods) {
        // shorter methods tends to be more generic and more likely used by high-level interfaces
        return methods.stream()
            .map(this::toString)
            .sorted(Comparator.comparing(String::length))
            .findFirst().orElse("");
    }

    private String getConstructorDefinition(Set<Constructor> constructors) {
        // shorter constructors tends to be more generic and more likely used by high-level interfaces
        return constructors.stream()
            .map(this::toString)
            .sorted(Comparator.comparing(String::length))
            .findFirst().orElse("");
    }

    private String getFieldDefinition(Set<Field> fields) {
        // longer field names tends to be more likely to be used by high-level interfaces
        return fields.stream()
            .map(this::toString)
            .sorted(Comparator.comparing(String::length).reversed())
            .findFirst().orElse("");
    }

    private String toString(Method method) {
        String className = method.getDeclaringClass().getName();
        String methodName = method.getName();
        String params = method.getParameters() == null
            ? ""
            : Arrays.stream(method.getParameters())
                .map(p -> p.getType().getCanonicalName())
                .collect(Collectors.joining(", "));
        return String.format("%s.%s(%s)", className, methodName, params);
    }

    private String toString(Constructor constructor) {
        String className = constructor.getDeclaringClass().getName();
        String constructorName = "<init>";
        String params = constructor.getParameters() == null
            ? ""
            : Arrays.stream(constructor.getParameters())
                .map(p -> p.getType().getCanonicalName())
                .collect(Collectors.joining(", "));
        return String.format("%s.%s(%s)", className, constructorName, params);
    }

    private String toString(Field field) {
        String className = field.getDeclaringClass().getName();
        String fieldName = field.getName();
        return String.format("%s.%s", className, fieldName);
    }

    private String className(String lambdaRefValue) {
        Matcher versionMatcher = LAMBDA_REF_PATTERN.matcher(lambdaRefValue);
        if (!versionMatcher.find()) {
            throw new IllegalStateException(String.format("Can't parse %s", lambdaRefValue));
        }

        return versionMatcher.group(1).trim();
    }

    private String refName(String lambdaRefValue) {
        Matcher versionMatcher = LAMBDA_REF_PATTERN.matcher(lambdaRefValue);
        if (!versionMatcher.find()) {
            return "";
        }

        return versionMatcher.group(2).trim();
    }
}
