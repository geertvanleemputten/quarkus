package io.quarkus.qute.deployment;

import static io.quarkus.deployment.annotations.ExecutionTime.STATIC_INIT;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.inject.Singleton;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget.Kind;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.BeanArchiveIndexBuildItem;
import io.quarkus.arc.deployment.BeanRegistrationPhaseBuildItem;
import io.quarkus.arc.deployment.BeanRegistrationPhaseBuildItem.BeanConfiguratorBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.arc.processor.DotNames;
import io.quarkus.deployment.ApplicationArchive;
import io.quarkus.deployment.GeneratedClassGizmoAdaptor;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.gizmo.AssignableResultHandle;
import io.quarkus.gizmo.BranchResult;
import io.quarkus.gizmo.BytecodeCreator;
import io.quarkus.gizmo.CatchBlockCreator;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.ClassCreator.Builder;
import io.quarkus.gizmo.ClassOutput;
import io.quarkus.gizmo.DescriptorUtils;
import io.quarkus.gizmo.FunctionCreator;
import io.quarkus.gizmo.MethodCreator;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.gizmo.ResultHandle;
import io.quarkus.gizmo.TryBlock;
import io.quarkus.qute.EvalContext;
import io.quarkus.qute.Expression;
import io.quarkus.qute.Expression.Part;
import io.quarkus.qute.Resolver;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.qute.deployment.QuteProcessor.Match;
import io.quarkus.qute.deployment.TemplatesAnalysisBuildItem.TemplateAnalysis;
import io.quarkus.qute.generator.Descriptors;
import io.quarkus.qute.generator.ValueResolverGenerator;
import io.quarkus.qute.i18n.Localized;
import io.quarkus.qute.i18n.Message;
import io.quarkus.qute.i18n.MessageBundle;
import io.quarkus.qute.i18n.MessageBundles;
import io.quarkus.qute.runtime.MessageBundleRecorder;
import io.quarkus.runtime.util.StringUtil;

public class MessageBundleProcessor {

    private static final Logger LOGGER = Logger.getLogger(MessageBundleProcessor.class);

    private static final String SUFFIX = "_Bundle";
    private static final String BUNDLE_DEFAULT_KEY = "defaultKey";
    private static final String BUNDLE_LOCALE = "locale";

    static final DotName BUNDLE = DotName.createSimple(MessageBundle.class.getName());
    static final DotName MESSAGE = DotName.createSimple(Message.class.getName());
    static final DotName LOCALIZED = DotName.createSimple(Localized.class.getName());

    static final MethodDescriptor TEMPLATE_INSTANCE = MethodDescriptor.ofMethod(Template.class, "instance",
            TemplateInstance.class);
    static final MethodDescriptor TEMPLATE_INSTANCE_DATA = MethodDescriptor.ofMethod(TemplateInstance.class, "data",
            TemplateInstance.class, String.class, Object.class);
    static final MethodDescriptor TEMPLATE_INSTANCE_RENDER = MethodDescriptor.ofMethod(TemplateInstance.class, "render",
            String.class);
    static final MethodDescriptor BUNDLES_GET_TEMPLATE = MethodDescriptor.ofMethod(MessageBundles.class, "getTemplate",
            Template.class, String.class);

    @BuildStep
    AdditionalBeanBuildItem beans() {
        return new AdditionalBeanBuildItem(MessageBundles.class, MessageBundle.class, Message.class, Localized.class);
    }

    @BuildStep
    List<MessageBundleBuildItem> processBundles(BeanArchiveIndexBuildItem beanArchiveIndex,
            ApplicationArchivesBuildItem applicationArchivesBuildItem,
            BuildProducer<GeneratedClassBuildItem> generatedClasses, BeanRegistrationPhaseBuildItem beanRegistration,
            BuildProducer<BeanConfiguratorBuildItem> configurators,
            BuildProducer<MessageBundleMethodBuildItem> messageTemplateMethods) throws IOException {

        IndexView index = beanArchiveIndex.getIndex();
        Map<String, ClassInfo> found = new HashMap<>();
        List<MessageBundleBuildItem> bundles = new ArrayList<>();
        Set<Path> messageFiles = findMessageFiles(applicationArchivesBuildItem);

        // First collect all interfaces annotated with @MessageBundle
        for (AnnotationInstance bundleAnnotation : index.getAnnotations(BUNDLE)) {
            if (bundleAnnotation.target().kind() == Kind.CLASS) {
                ClassInfo bundleClass = bundleAnnotation.target().asClass();
                if (Modifier.isInterface(bundleClass.flags())) {
                    AnnotationValue nameValue = bundleAnnotation.value();
                    String name = nameValue != null ? nameValue.asString() : MessageBundle.DEFAULT_NAME;

                    if (found.containsKey(name)) {
                        throw new MessageBundleException(
                                String.format("Message bundle interface name conflict - [%s] is used for both [%s] and [%s]",
                                        name, bundleClass, found.get(name)));
                    }
                    found.put(name, bundleClass);

                    // Find localizations for each interface
                    String defaultLocale = getDefaultLocale(bundleAnnotation);
                    List<ClassInfo> localized = new ArrayList<>();
                    for (ClassInfo implementor : index.getKnownDirectImplementors(bundleClass.name())) {
                        if (Modifier.isInterface(implementor.flags())) {
                            localized.add(implementor);
                        }
                    }
                    Map<String, ClassInfo> localeToInterface = new HashMap<>();
                    for (ClassInfo localizedInterface : localized) {
                        String locale = localizedInterface.classAnnotation(LOCALIZED).value().asString();
                        ClassInfo previous = localeToInterface.put(locale, localizedInterface);
                        if (defaultLocale.equals(locale) || previous != null) {
                            throw new MessageBundleException(String.format(
                                    "A localized message bundle interface [%s] already exists for locale %s: [%s]",
                                    previous != null ? previous : bundleClass, localizedInterface));
                        }
                    }

                    // Find localized files
                    Map<String, Path> localeToFile = new HashMap<>();
                    for (Path messageFile : messageFiles) {
                        String fileName = messageFile.getFileName().toString();
                        if (fileName.startsWith(name)) {
                            // msg_en.txt -> en
                            String locale = fileName.substring(fileName.indexOf('_') + 1, fileName.indexOf('.'));
                            ClassInfo localizedInterface = localeToInterface.get(locale);
                            if (localizedInterface != null) {
                                throw new MessageBundleException(
                                        String.format(
                                                "A localized message bundle interface [%s] already exists for locale %s: [%s]",
                                                localizedInterface, locale, fileName));
                            }
                            localeToFile.put(locale, messageFile);
                        }
                    }

                    bundles.add(new MessageBundleBuildItem(name, bundleClass, localeToInterface, localeToFile));
                } else {
                    throw new MessageBundleException("@MessageBundle must be declared on an interface: " + bundleClass);
                }
            }
        }

        // Generate implementations
        // name -> impl class
        Map<String, String> generatedImplementations = generateImplementations(bundles,
                applicationArchivesBuildItem, generatedClasses, messageTemplateMethods);

        // Register synthetic beans
        for (MessageBundleBuildItem bundle : bundles) {
            ClassInfo bundleInterface = bundle.getDefaultBundleInterface();
            beanRegistration.getContext().configure(bundleInterface.name()).addType(bundle.getDefaultBundleInterface().name())
                    // The default message bundle - add both @Default and @Localized
                    .addQualifier(DotNames.DEFAULT).addQualifier().annotation(LOCALIZED)
                    .addValue("value", getDefaultLocale(bundleInterface.classAnnotation(BUNDLE))).done().unremovable()
                    .scope(Singleton.class).creator(mc -> {
                        // Just create a new instance of the generated class
                        mc.returnValue(
                                mc.newInstance(MethodDescriptor
                                        .ofConstructor(generatedImplementations.get(bundleInterface.name().toString()))));
                    }).done();

            // Localized interfaces
            for (ClassInfo localizedInterface : bundle.getLocalizedInterfaces().values()) {
                beanRegistration.getContext().configure(localizedInterface.name())
                        .addType(bundle.getDefaultBundleInterface().name())
                        .addQualifier(localizedInterface.classAnnotation(LOCALIZED))
                        .unremovable()
                        .scope(Singleton.class).creator(mc -> {
                            // Just create a new instance of the generated class
                            mc.returnValue(
                                    mc.newInstance(MethodDescriptor.ofConstructor(
                                            generatedImplementations.get(localizedInterface.name().toString()))));
                        }).done();
            }
            // Localized files
            for (Entry<String, Path> entry : bundle.getLocalizedFiles().entrySet()) {
                beanRegistration.getContext().configure(bundle.getDefaultBundleInterface().name())
                        .addType(bundle.getDefaultBundleInterface().name())
                        .addQualifier().annotation(LOCALIZED)
                        .addValue("value", entry.getKey()).done()
                        .unremovable()
                        .scope(Singleton.class).creator(mc -> {
                            // Just create a new instance of the generated class
                            mc.returnValue(
                                    mc.newInstance(MethodDescriptor
                                            .ofConstructor(generatedImplementations.get(entry.getValue().toString()))));
                        }).done();
            }
        }

        return bundles;
    }

    @Record(value = STATIC_INIT)
    @BuildStep
    void initBundleContext(MessageBundleRecorder recorder,
            List<MessageBundleMethodBuildItem> messageBundleMethods,
            List<MessageBundleBuildItem> bundles,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeans) throws ClassNotFoundException {

        Map<String, Map<String, Class<?>>> bundleInterfaces = new HashMap<>();
        for (MessageBundleBuildItem bundle : bundles) {
            final Class<?> bundleClass = Class.forName(bundle.getDefaultBundleInterface().toString(), true,
                    Thread.currentThread().getContextClassLoader());
            Map<String, Class<?>> localeToInterface = new HashMap<>();
            localeToInterface.put(MessageBundles.DEFAULT_LOCALE,
                    bundleClass);

            for (String locale : bundle.getLocalizedInterfaces().keySet()) {
                localeToInterface.put(locale, bundleClass);
            }
            for (String locale : bundle.getLocalizedFiles().keySet()) {
                localeToInterface.put(locale, bundleClass);
            }
            bundleInterfaces.put(bundle.getName(), localeToInterface);
        }

        Map<String, String> templateIdToContent = messageBundleMethods.stream().collect(
                Collectors.toMap(MessageBundleMethodBuildItem::getTemplateId, MessageBundleMethodBuildItem::getTemplate));

        syntheticBeans.produce(SyntheticBeanBuildItem.configure(MessageBundleRecorder.BundleContext.class)
                .supplier(recorder.createContext(templateIdToContent, bundleInterfaces)).done());
    }

    @BuildStep
    void validateMessageBundleMethods(TemplatesAnalysisBuildItem templatesAnalysis,
            List<MessageBundleMethodBuildItem> messageBundleMethods,
            BuildProducer<IncorrectExpressionBuildItem> incorrectExpressions) {

        Map<String, MessageBundleMethodBuildItem> bundleMethods = messageBundleMethods.stream()
                .collect(Collectors.toMap(MessageBundleMethodBuildItem::getTemplateId, Function.identity()));

        for (TemplateAnalysis analysis : templatesAnalysis.getAnalysis()) {
            MessageBundleMethodBuildItem messageBundleMethod = bundleMethods.get(analysis.id);
            if (messageBundleMethod != null) {
                Set<String> usedParamNames = new HashSet<>();
                // All top-level expressions without namespace map to a param
                Set<String> paramNames = IntStream.range(0, messageBundleMethod.getMethod().parameters().size())
                        .mapToObj(idx -> messageBundleMethod.getMethod().parameterName(idx)).collect(Collectors.toSet());
                for (Expression expression : analysis.expressions) {
                    if (expression.isLiteral() || expression.hasNamespace()) {
                        continue;
                    }
                    String name = expression.getParts().get(0).getName();
                    if (!paramNames.contains(name)) {
                        incorrectExpressions.produce(new IncorrectExpressionBuildItem(expression.toOriginalString(),
                                name + " is not a parameter of the message bundle method "
                                        + messageBundleMethod.getMethod().declaringClass().name() + "#"
                                        + messageBundleMethod.getMethod().name() + "()",
                                expression.getOrigin().getLine(),
                                expression.getOrigin().getTemplateGeneratedId()));
                    } else {
                        usedParamNames.add(name);
                    }
                }

                // Log a warning if a parameter is not used in the template
                for (String paramName : paramNames) {
                    if (!usedParamNames.contains(paramName)) {
                        LOGGER.warnf("Unused parameter found [%s] in the message template of: %s", paramName,
                                messageBundleMethod.getMethod().declaringClass().name() + "#"
                                        + messageBundleMethod.getMethod().name() + "()");
                    }
                }
            }
        }
    }

    @BuildStep
    void validateMessageBundleMethodsInTemplates(TemplatesAnalysisBuildItem analysis,
            BeanArchiveIndexBuildItem beanArchiveIndex,
            List<TemplateExtensionMethodBuildItem> templateExtensionMethods,
            List<TypeCheckExcludeBuildItem> excludes,
            List<MessageBundleBuildItem> messageBundles,
            List<MessageBundleMethodBuildItem> messageBundleMethods,
            BuildProducer<IncorrectExpressionBuildItem> incorrectExpressions,
            BuildProducer<ImplicitValueResolverBuildItem> implicitClasses) {

        IndexView index = beanArchiveIndex.getIndex();
        Function<String, String> templateIdToPathFun = new Function<String, String>() {
            @Override
            public String apply(String id) {
                return QuteProcessor.findTemplatePath(analysis, id);
            }
        };

        // bundle name -> (key -> method)
        Map<String, Map<String, MethodInfo>> bundleMethodsMap = new HashMap<>();
        for (MessageBundleMethodBuildItem messageBundleMethod : messageBundleMethods) {
            Map<String, MethodInfo> bundleMethods = bundleMethodsMap.get(messageBundleMethod.getBundleName());
            if (bundleMethods == null) {
                bundleMethods = new HashMap<>();
                bundleMethodsMap.put(messageBundleMethod.getBundleName(), bundleMethods);
            }
            bundleMethods.put(messageBundleMethod.getKey(), messageBundleMethod.getMethod());
        }
        // bundle name -> bundle interface
        Map<String, ClassInfo> bundlesMap = new HashMap<>();
        for (MessageBundleBuildItem messageBundle : messageBundles) {
            bundlesMap.put(messageBundle.getName(), messageBundle.getDefaultBundleInterface());
        }

        for (Entry<String, Map<String, MethodInfo>> entry : bundleMethodsMap.entrySet()) {

            Set<Expression> expressions = QuteProcessor.collectNamespaceExpressions(analysis, entry.getKey());
            Map<String, MethodInfo> methods = entry.getValue();
            ClassInfo defaultBundleInterface = bundlesMap.get(entry.getKey());

            if (!expressions.isEmpty()) {

                // Map implicit class -> true if methods were used
                Map<ClassInfo, Boolean> implicitClassToMethodUsed = new HashMap<>();

                for (Expression expression : expressions) {

                    // msg:hello_world(foo.name)
                    Part methodPart = expression.getParts().get(0);
                    MethodInfo method = methods.get(methodPart.getName());

                    if (method == null) {
                        if (!methodPart.isVirtualMethod() || methodPart.asVirtualMethod().getParameters().isEmpty()) {
                            // The method template may contain no expressions
                            method = defaultBundleInterface.method(methodPart.getName());
                        }
                        if (method == null) {
                            // User is referencing a non-existent message
                            incorrectExpressions.produce(new IncorrectExpressionBuildItem(expression.toOriginalString(),
                                    "Message bundle [name=" + entry.getKey() + ", interface="
                                            + defaultBundleInterface
                                            + "] does not define a method for key: " + methodPart.getName(),
                                    expression.getOrigin().getLine(),
                                    expression.getOrigin().getTemplateGeneratedId()));
                            continue;
                        }
                    }

                    if (methodPart.isVirtualMethod()) {
                        // For virtual method validate the number of params first  
                        List<Expression> params = methodPart.asVirtualMethod().getParameters();
                        List<Type> methodParams = method.parameters();

                        if (methodParams.size() != params.size()) {
                            incorrectExpressions.produce(new IncorrectExpressionBuildItem(expression.toOriginalString(),
                                    "Message bundle [name=" + entry.getKey() + ", interface="
                                            + defaultBundleInterface
                                            + "] - wrong number of parameters for method: " + method.toString(),
                                    expression.getOrigin().getLine(),
                                    expression.getOrigin().getTemplateGeneratedId()));
                            continue;
                        }

                        // Then attempt to validate the parameter types
                        int idx = 0;
                        for (Expression param : params) {
                            if (param.hasTypeInfo()) {
                                Map<String, Match> results = new HashMap<>();
                                QuteProcessor.validateNestedExpressions(defaultBundleInterface, results,
                                        templateExtensionMethods, excludes,
                                        incorrectExpressions, expression, index, implicitClassToMethodUsed,
                                        templateIdToPathFun);
                                Match match = results.get(param.toOriginalString());
                                if (match != null && !Types.isAssignableFrom(match.type,
                                        methodParams.get(idx), index)) {
                                    incorrectExpressions.produce(new IncorrectExpressionBuildItem(expression.toOriginalString(),
                                            "Message bundle method " + method.declaringClass().name() + "#" +
                                                    method.name() + "() parameter [" + method.parameterName(idx)
                                                    + "] does not match the type: " + match.type,
                                            expression.getOrigin().getLine(),
                                            expression.getOrigin().getTemplateGeneratedId()));
                                }
                            }
                            idx++;
                        }
                    }
                }

                for (Entry<ClassInfo, Boolean> implicit : implicitClassToMethodUsed.entrySet()) {
                    implicitClasses.produce(implicit.getValue()
                            ? new ImplicitValueResolverBuildItem(implicit.getKey(),
                                    new TemplateDataBuilder().properties(false).build())
                            : new ImplicitValueResolverBuildItem(implicit.getKey()));
                }
            }
        }
    }

    private Map<String, String> generateImplementations(List<MessageBundleBuildItem> bundles,
            ApplicationArchivesBuildItem applicationArchivesBuildItem,
            BuildProducer<GeneratedClassBuildItem> generatedClasses,
            BuildProducer<MessageBundleMethodBuildItem> messageTemplateMethods) throws IOException {

        Map<String, String> generatedTypes = new HashMap<>();

        ClassOutput classOutput = new GeneratedClassGizmoAdaptor(generatedClasses, new Predicate<String>() {
            @Override
            public boolean test(String name) {
                int idx = name.lastIndexOf(SUFFIX);
                String className = name.substring(0, idx).replace("/", ".");
                if (className.contains(ValueResolverGenerator.NESTED_SEPARATOR)) {
                    className = className.replace(ValueResolverGenerator.NESTED_SEPARATOR, "$");
                }
                if (applicationArchivesBuildItem.getRootArchive().getIndex()
                        .getClassByName(DotName.createSimple(className)) != null) {
                    return true;
                }
                return false;
            }
        });

        for (MessageBundleBuildItem bundle : bundles) {
            ClassInfo bundleInterface = bundle.getDefaultBundleInterface();
            String bundleImpl = generateImplementation(null, null, bundleInterface, classOutput, messageTemplateMethods,
                    Collections.emptyMap(), null);
            generatedTypes.put(bundleInterface.name().toString(), bundleImpl);
            for (ClassInfo localizedInterface : bundle.getLocalizedInterfaces().values()) {
                generatedTypes.put(localizedInterface.name().toString(),
                        generateImplementation(bundle.getDefaultBundleInterface(), bundleImpl, localizedInterface, classOutput,
                                messageTemplateMethods, Collections.emptyMap(), null));
            }

            for (Entry<String, Path> entry : bundle.getLocalizedFiles().entrySet()) {
                Path localizedFile = entry.getValue();
                Map<String, String> keyToTemplate = new HashMap<>();
                int linesProcessed = 0;
                for (String line : Files.readAllLines(localizedFile)) {
                    linesProcessed++;
                    if (!line.startsWith("#")) {
                        int eqIndex = line.indexOf('=');
                        if (eqIndex == -1) {
                            throw new MessageBundleException(
                                    "Missing key/value separator\n\t- file: " + localizedFile + "\n\t- line " + linesProcessed);
                        }
                        String key = line.substring(0, eqIndex).trim();
                        if (!hasMessageBundleMethod(bundleInterface, key)) {
                            throw new MessageBundleException(
                                    "Message bundle method " + key + "() not found on: " + bundleInterface + "\n\t- file: "
                                            + localizedFile + "\n\t- line " + linesProcessed);
                        }
                        String value = line.substring(eqIndex + 1, line.length()).trim();
                        keyToTemplate.put(key, value);
                    }
                }
                generatedTypes.put(localizedFile.toString(),
                        generateImplementation(bundle.getDefaultBundleInterface(), bundleImpl, bundleInterface, classOutput,
                                messageTemplateMethods, keyToTemplate, entry.getKey()));
            }
        }
        return generatedTypes;
    }

    private boolean hasMessageBundleMethod(ClassInfo bundleInterface, String name) {
        for (MethodInfo method : bundleInterface.methods()) {
            if (method.name().equals(name) && method.hasAnnotation(MESSAGE)) {
                return true;
            }
        }
        return false;
    }

    private String generateImplementation(ClassInfo defaultBundleInterface, String defaultBundleImpl, ClassInfo bundleInterface,
            ClassOutput classOutput, BuildProducer<MessageBundleMethodBuildItem> messageTemplateMethods,
            Map<String, String> messageTemplates, String locale) {

        LOGGER.debugf("Generate bundle implementation for %s", bundleInterface);
        AnnotationInstance bundleAnnotation = defaultBundleInterface != null ? defaultBundleInterface.classAnnotation(BUNDLE)
                : bundleInterface.classAnnotation(BUNDLE);
        AnnotationValue nameValue = bundleAnnotation.value();
        String bundleName = nameValue != null ? nameValue.asString() : MessageBundle.DEFAULT_NAME;
        AnnotationValue defaultKeyValue = bundleAnnotation.value(BUNDLE_DEFAULT_KEY);

        String baseName;
        if (bundleInterface.enclosingClass() != null) {
            baseName = DotNames.simpleName(bundleInterface.enclosingClass()) + "_"
                    + DotNames.simpleName(bundleInterface.name());
        } else {
            baseName = DotNames.simpleName(bundleInterface.name());
        }
        if (locale != null) {
            baseName = baseName + "_" + locale;
        }

        String targetPackage = DotNames.packageName(bundleInterface.name());
        String generatedName = targetPackage.replace('.', '/') + "/" + baseName + SUFFIX;

        // MyMessages_Bundle implements MyMessages, Resolver
        Builder builder = ClassCreator.builder().classOutput(classOutput).className(generatedName)
                .interfaces(bundleInterface.name().toString(), Resolver.class.getName());
        if (defaultBundleImpl != null) {
            builder.superClass(defaultBundleImpl);
        }
        ClassCreator bundleCreator = builder.build();

        // key -> method
        Map<String, MethodInfo> keyMap = new HashMap<>();

        for (MethodInfo method : bundleInterface.methods()) {
            LOGGER.debugf("Found message bundle method %s on %s", method, bundleInterface);

            MethodCreator bundleMethod = bundleCreator.getMethodCreator(MethodDescriptor.of(method));

            AnnotationInstance messageAnnotation;
            if (defaultBundleInterface != null) {
                MethodInfo defaultBundleMethod = bundleInterface.method(method.name(),
                        method.parameters().toArray(new Type[] {}));
                if (defaultBundleMethod == null) {
                    throw new MessageBundleException(
                            String.format("Default bundle method not found on %s: %s", bundleInterface, method));
                }
                messageAnnotation = defaultBundleMethod.annotation(MESSAGE);
            } else {
                messageAnnotation = method.annotation(MESSAGE);
            }

            if (messageAnnotation == null) {
                throw new MessageBundleException(
                        "A message bundle interface method must be annotated with @Message: " + method);
            }

            String key = getKey(method, messageAnnotation, defaultKeyValue);
            if (keyMap.containsKey(key)) {
                throw new MessageBundleException(String.format("Duplicate key [%s] found on %s", key, bundleInterface));
            }
            keyMap.put(key, method);

            String messageTemplate = messageTemplates.get(method.name());
            if (messageTemplate == null) {
                messageTemplate = messageAnnotation.value().asString();
            }
            if (!messageTemplate.contains("}")) {
                // No expression/tag - no need to use qute
                bundleMethod.returnValue(bundleMethod.load(messageTemplate));
            } else {
                // Obtain the template, e.g. msg_hello_name
                String templateId;

                if (defaultBundleInterface != null) {
                    if (locale == null) {
                        AnnotationInstance localizedAnnotation = bundleInterface.classAnnotation(LOCALIZED);
                        locale = localizedAnnotation.value().asString();
                    }
                    templateId = bundleName + "_" + locale + "_" + key;
                } else {
                    templateId = bundleName + "_" + key;
                }

                messageTemplateMethods
                        .produce(new MessageBundleMethodBuildItem(bundleName, key, templateId, method, messageTemplate));

                ResultHandle template = bundleMethod.invokeStaticMethod(BUNDLES_GET_TEMPLATE,
                        bundleMethod.load(templateId));
                // Create a template instance
                ResultHandle templateInstance = bundleMethod.invokeInterfaceMethod(TEMPLATE_INSTANCE, template);
                List<Type> paramTypes = method.parameters();
                if (!paramTypes.isEmpty()) {
                    // Set data
                    int i = 0;
                    Iterator<Type> it = paramTypes.iterator();
                    while (it.hasNext()) {
                        String name = method.parameterName(i);
                        if (name == null) {
                            // TODO should we just log a warning and use arg0 etc.?
                            throw new MessageBundleException("Null param name for index " + i + ": " + method);
                        }
                        bundleMethod.invokeInterfaceMethod(TEMPLATE_INSTANCE_DATA, templateInstance,
                                bundleMethod.load(name), bundleMethod.getMethodParam(i));
                        i++;
                        it.next();
                    }
                }
                // Render the template
                // At this point it's already validated that the method returns String
                bundleMethod.returnValue(bundleMethod.invokeInterfaceMethod(TEMPLATE_INSTANCE_RENDER, templateInstance));
            }
        }

        implementResolve(defaultBundleImpl, bundleCreator, keyMap);

        bundleCreator.close();
        return generatedName.replace('/', '.');
    }

    private void implementResolve(String defaultBundleImpl, ClassCreator bundleCreator, Map<String, MethodInfo> keyMap) {
        MethodCreator resolve = bundleCreator.getMethodCreator("resolve", CompletionStage.class, EvalContext.class);

        ResultHandle evalContext = resolve.getMethodParam(0);
        ResultHandle name = resolve.invokeInterfaceMethod(Descriptors.GET_NAME, evalContext);
        ResultHandle ret = resolve.newInstance(MethodDescriptor.ofConstructor(CompletableFuture.class));

        // Group messages to workaround limits of a java method body 
        final int groupLimit = 300;
        int groupIndex = 0;
        int resolveIndex = 0;
        MethodCreator resolveGroup = null;

        for (Entry<String, MethodInfo> entry : keyMap.entrySet()) {
            if (resolveGroup == null || groupIndex++ >= groupLimit) {
                if (resolveGroup != null) {
                    resolveGroup.returnValue(resolveGroup.loadNull());
                }
                groupIndex = 0;
                String resolveMethodName = "resolve_" + resolveIndex++;
                resolveGroup = bundleCreator.getMethodCreator(resolveMethodName, CompletableFuture.class, String.class,
                        EvalContext.class, CompletableFuture.class).setModifiers(ACC_PRIVATE | ACC_FINAL);
                ResultHandle resRet = resolve.invokeVirtualMethod(
                        MethodDescriptor.ofMethod(bundleCreator.getClassName(), resolveMethodName, CompletableFuture.class,
                                String.class, EvalContext.class, CompletableFuture.class),
                        resolve.getThis(), name, evalContext, ret);
                resolve.ifNotNull(resRet).trueBranch().returnValue(resRet);
            }
            addMessageMethod(resolveGroup, entry.getKey(), entry.getValue(), resolveGroup.getMethodParam(0),
                    resolveGroup.getMethodParam(1), resolveGroup.getMethodParam(2), bundleCreator.getClassName());
        }

        if (resolveGroup != null) {
            resolveGroup.returnValue(resolveGroup.loadNull());
        }

        if (defaultBundleImpl != null) {
            resolve.returnValue(resolve.invokeSpecialMethod(
                    MethodDescriptor.ofMethod(defaultBundleImpl, "resolve", CompletionStage.class, EvalContext.class),
                    resolve.getThis(), evalContext));
        } else {
            resolve.returnValue(resolve.readStaticField(Descriptors.RESULTS_NOT_FOUND));
        }
    }

    private void addMessageMethod(MethodCreator resolve, String key, MethodInfo method, ResultHandle name,
            ResultHandle evalContext,
            ResultHandle ret, String bundleClass) {
        List<Type> methodParams = method.parameters();

        BytecodeCreator matched = resolve.ifNonZero(resolve.invokeVirtualMethod(Descriptors.EQUALS,
                resolve.load(key), name))
                .trueBranch();
        if (method.parameters().isEmpty()) {
            matched.invokeVirtualMethod(Descriptors.COMPLETABLE_FUTURE_COMPLETE, ret,
                    matched.invokeInterfaceMethod(method, matched.getThis()));
            matched.returnValue(ret);
        } else {
            // The CompletionStage upon which we invoke whenComplete()
            ResultHandle evaluatedParams = matched.invokeStaticMethod(Descriptors.EVALUATED_PARAMS_EVALUATE,
                    evalContext);
            ResultHandle paramsReady = matched.readInstanceField(Descriptors.EVALUATED_PARAMS_STAGE,
                    evaluatedParams);

            FunctionCreator whenCompleteFun = matched.createFunction(BiConsumer.class);
            matched.invokeInterfaceMethod(Descriptors.CF_WHEN_COMPLETE, paramsReady, whenCompleteFun.getInstance());

            BytecodeCreator whenComplete = whenCompleteFun.getBytecode();

            AssignableResultHandle whenThis = whenComplete
                    .createVariable(DescriptorUtils.extToInt(bundleClass));
            whenComplete.assign(whenThis, matched.getThis());
            AssignableResultHandle whenRet = whenComplete.createVariable(CompletableFuture.class);
            whenComplete.assign(whenRet, ret);

            BranchResult throwableIsNull = whenComplete.ifNull(whenComplete.getMethodParam(1));

            // complete
            BytecodeCreator success = throwableIsNull.trueBranch();

            ResultHandle[] paramsHandle = new ResultHandle[methodParams.size()];
            if (methodParams.size() == 1) {
                paramsHandle[0] = whenComplete.getMethodParam(0);
            } else {
                for (int i = 0; i < methodParams.size(); i++) {
                    paramsHandle[i] = success.invokeVirtualMethod(Descriptors.EVALUATED_PARAMS_GET_RESULT,
                            evaluatedParams,
                            success.load(i));
                }
            }

            AssignableResultHandle invokeRet = success.createVariable(Object.class);
            // try
            TryBlock tryCatch = success.tryBlock();
            // catch (Throwable e)
            CatchBlockCreator exception = tryCatch.addCatch(Throwable.class);
            // CompletableFuture.completeExceptionally(Throwable)
            exception.invokeVirtualMethod(Descriptors.COMPLETABLE_FUTURE_COMPLETE_EXCEPTIONALLY, whenRet,
                    exception.getCaughtException());

            tryCatch.assign(invokeRet,
                    tryCatch.invokeInterfaceMethod(MethodDescriptor.of(method), whenThis, paramsHandle));

            tryCatch.invokeVirtualMethod(Descriptors.COMPLETABLE_FUTURE_COMPLETE, whenRet, invokeRet);
            // CompletableFuture.completeExceptionally(Throwable)
            BytecodeCreator failure = throwableIsNull.falseBranch();
            failure.invokeVirtualMethod(Descriptors.COMPLETABLE_FUTURE_COMPLETE_EXCEPTIONALLY, whenRet,
                    whenComplete.getMethodParam(1));
            whenComplete.returnValue(null);

            matched.returnValue(ret);
        }
    }

    @SuppressWarnings("deprecation")
    private String getKey(MethodInfo method, AnnotationInstance messageAnnotation, AnnotationValue defaultKeyValue) {
        AnnotationValue keyValue = messageAnnotation.value("key");
        String key;
        if (keyValue == null) {
            // Use the strategy from @MessageBundle
            key = defaultKeyValue != null ? defaultKeyValue.asString() : Message.ELEMENT_NAME;
        } else {
            key = keyValue.asString();
        }
        switch (key) {
            case Message.ELEMENT_NAME:
                return method.name();
            case Message.HYPHENATED_ELEMENT_NAME:
                return StringUtil.hyphenate(method.name());
            case Message.UNDERSCORED_ELEMENT_NAME:
                return StringUtil.join("_", StringUtil.lowerCase(StringUtil.camelHumpsIterator(method.name())));
            default:
                return keyValue.asString();
        }
    }

    private String getDefaultLocale(AnnotationInstance bundleAnnotation) {
        AnnotationValue localeValue = bundleAnnotation.value(BUNDLE_LOCALE);
        String defaultLocale;
        if (localeValue == null || localeValue.asString().equals(MessageBundle.DEFAULT_LOCALE)) {
            defaultLocale = Locale.getDefault().toLanguageTag();
        } else {
            defaultLocale = localeValue.asString();
        }
        return defaultLocale;
    }

    private Set<Path> findMessageFiles(ApplicationArchivesBuildItem applicationArchivesBuildItem) throws IOException {
        ApplicationArchive applicationArchive = applicationArchivesBuildItem.getRootArchive();
        String basePath = "messages";
        Path messagesPath = applicationArchive.getChildPath(basePath);
        if (messagesPath == null) {
            return Collections.emptySet();
        }
        Set<Path> messageFiles = new HashSet<>();
        try (Stream<Path> files = Files.list(messagesPath)) {
            Iterator<Path> iter = files.iterator();
            while (iter.hasNext()) {
                Path filePath = iter.next();
                if (Files.isRegularFile(filePath)) {
                    messageFiles.add(filePath);
                }
            }
        }
        return messageFiles;
    }

}
