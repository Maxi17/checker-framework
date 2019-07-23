package org.checkerframework.framework.util;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ArrayType;
import com.sun.tools.javac.code.Type.ClassType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import org.checkerframework.checker.compilermsgs.qual.CompilerMessageKey;
import org.checkerframework.dataflow.analysis.FlowExpressions;
import org.checkerframework.dataflow.analysis.FlowExpressions.ArrayAccess;
import org.checkerframework.dataflow.analysis.FlowExpressions.ClassName;
import org.checkerframework.dataflow.analysis.FlowExpressions.FieldAccess;
import org.checkerframework.dataflow.analysis.FlowExpressions.LocalVariable;
import org.checkerframework.dataflow.analysis.FlowExpressions.MethodCall;
import org.checkerframework.dataflow.analysis.FlowExpressions.Receiver;
import org.checkerframework.dataflow.analysis.FlowExpressions.ThisReference;
import org.checkerframework.dataflow.analysis.FlowExpressions.ValueLiteral;
import org.checkerframework.dataflow.cfg.node.ClassNameNode;
import org.checkerframework.dataflow.cfg.node.ImplicitThisLiteralNode;
import org.checkerframework.dataflow.cfg.node.LocalVariableNode;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.dataflow.cfg.node.Node;
import org.checkerframework.dataflow.cfg.node.ObjectCreationNode;
import org.checkerframework.framework.source.Result;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.util.dependenttypes.DependentTypesError;
import org.checkerframework.javacutil.ElementUtils;
import org.checkerframework.javacutil.Resolver;
import org.checkerframework.javacutil.TreeUtils;
import org.checkerframework.javacutil.TypesUtils;
import org.checkerframework.javacutil.trees.TreeBuilder;

/**
 * A collection of helper methods to parse a string that represents a restricted Java expression.
 *
 * @checker_framework.manual #java-expressions-as-arguments Writing Java expressions as annotation
 *     arguments
 * @checker_framework.manual #dependent-types Annotations whose argument is a Java expression
 *     (dependent type annotations)
 */
public class FlowExpressionParseUtil {

    /** Regular expression for a formal parameter use. */
    protected static final String PARAMETER_REGEX = "#([1-9][0-9]*)";

    /** Unanchored; can be used to find all formal parameter uses. */
    protected static final Pattern UNANCHORED_PARAMETER_PATTERN = Pattern.compile(PARAMETER_REGEX);

    /**
     * Parse a string and return its representation as a {@link Receiver}, or throw an {@link
     * FlowExpressionParseException}.
     *
     * @param expression flow expression to parse
     * @param context information about any receiver and arguments
     * @param localScope path to local scope to use
     * @param useLocalScope whether {@code localScope} should be used to resolve identifiers
     */
    public static Receiver parse(
            String expression,
            FlowExpressionContext context,
            TreePath localScope,
            boolean useLocalScope)
            throws FlowExpressionParseException {
        context = context.copyAndSetUseLocalScope(useLocalScope);
        Expression expr;
        try {
            expr = StaticJavaParser.parseExpression(replaceParameterSyntax(expression));
        } catch (ParseProblemException e) {
            throw constructParserException(expression, "is an invalid expression");
        }
        Receiver result = getReceiverFromExpression(expr, context, localScope);
        if (result instanceof ClassName && !expression.endsWith("class")) {
            throw constructParserException(
                    expression, "a class name cannot terminate a flow expression string");
        }
        return result;
    }

    /**
     * Converts the passed Javaparser Expression to a {@link Receiver}.
     *
     * @return the Receiver of the passed expression
     */
    private static Receiver getReceiverFromExpression(
            Expression expr, FlowExpressionContext context, TreePath path)
            throws FlowExpressionParseException {

        ProcessingEnvironment env = context.checkerContext.getProcessingEnvironment();
        Types types = env.getTypeUtils();

        if (expr != null) {
            if (expr.isNullLiteralExpr()) {
                return new ValueLiteral(types.getNullType(), (Object) null);
            } else if (expr.isIntegerLiteralExpr()) {
                return new ValueLiteral(
                        types.getPrimitiveType(TypeKind.INT), expr.asIntegerLiteralExpr().asInt());
            } else if (expr.isLongLiteralExpr()) {
                return new ValueLiteral(
                        types.getPrimitiveType(TypeKind.LONG), expr.asLongLiteralExpr().asLong());
            } else if (expr.isDoubleLiteralExpr()) {
                return new ValueLiteral(
                        types.getPrimitiveType(TypeKind.DOUBLE),
                        expr.asDoubleLiteralExpr().asDouble());
            } else if (expr.isStringLiteralExpr()) {
                TypeMirror stringTM =
                        TypesUtils.typeFromClass(String.class, types, env.getElementUtils());
                return new ValueLiteral(stringTM, expr.asStringLiteralExpr().asString());
            } else if (expr.isThisExpr() && !expr.asThisExpr().getTypeName().isPresent()) {
                return getThisReceiver(context);
            } else if (expr.isSuperExpr()) {
                return getSuperReceiver(types, context);
            } else if (expr.isEnclosedExpr()) {
                return getReceiverFromExpression(expr.asEnclosedExpr().getInner(), context, path);
            } else if (expr.isArrayAccessExpr()) {
                return getArrayAccessReceiver(expr.asArrayAccessExpr(), context, path);
            } else if (expr.isNameExpr()) {
                return getIdentifierReceiver(
                        expr.asNameExpr().getNameAsString(), env, path, context);
            } else if (expr.isMethodCallExpr()) {
                return getMethodCallReceiver(expr.asMethodCallExpr(), context, path, env);
            } else if (expr.isFieldAccessExpr()) {
                return getMemberSelectReceiver(expr.asFieldAccessExpr(), env, context, path);
            } else if (expr.isClassExpr()) {
                // Class literals.
                // The parsing returns a NameExpr to be handled by getIdentifierReceiver
                // or a FieldAccessExpr to be handled by getMemberSelectReceiver.
                return getReceiverFromExpression(
                        StaticJavaParser.parseExpression(expr.asClassExpr().getTypeAsString()),
                        context,
                        path);
            } else {
                String message;
                if (expr.toString().equals("_param_0")) {
                    message =
                            "one should use \"this\" for the receiver or \"#1\" for the first formal parameter";
                } else {
                    message = "is an unrecognized expression";
                }
                if (context.parsingMember) {
                    message += " in a context with parsingMember=true";
                }
                throw constructParserException(expr.toString(), message);
            }
        }

        return null;
    }

    /**
     * Replaces every occurrence of "#(number)" with "_param_(number)" where number is an index of a
     * parameter.
     */
    private static String replaceParameterSyntax(String expression) {
        String updatedExpression = expression;

        for (Integer integer : parameterIndices(expression)) {
            updatedExpression = updatedExpression.replaceAll("#" + integer, "_param_" + integer);
        }

        return updatedExpression;
    }

    /** @param expr a field access, a fully qualified class name, or qualified class name */
    private static Receiver getMemberSelectReceiver(
            FieldAccessExpr expr,
            ProcessingEnvironment env,
            FlowExpressionContext context,
            TreePath path)
            throws FlowExpressionParseException {
        Resolver resolver = new Resolver(env);

        Symbol.PackageSymbol packageSymbol = resolver.findPackage(expr.getScope().toString(), path);
        if (packageSymbol != null) {
            ClassSymbol classSymbol =
                    resolver.findClassInPackage(expr.getNameAsString(), packageSymbol, path);
            if (classSymbol != null) {
                return new ClassName(classSymbol.asType());
            }
            throw constructParserException(
                    expr.toString(),
                    "could not find class "
                            + expr.getNameAsString()
                            + " inside "
                            + expr.getScope().toString());
        }

        Receiver receiver = getReceiverFromExpression(expr.getScope(), context, path);

        // Parse the rest, with a new receiver.
        FlowExpressionContext newContext = context.copyChangeToParsingMemberOfReceiver(receiver);
        return getReceiverFromExpression(expr.getNameAsExpression(), newContext, path);
    }

    private static Receiver getThisReceiver(FlowExpressionContext context) {
        if (!(context.receiver == null || context.receiver.containsUnknown())) {
            // "this" is the receiver of the context
            return context.receiver;
        }
        return new ThisReference(context.receiver == null ? null : context.receiver.getType());
    }

    private static Receiver getSuperReceiver(Types types, FlowExpressionContext context)
            throws FlowExpressionParseException {
        // super literal
        List<? extends TypeMirror> superTypes = types.directSupertypes(context.receiver.getType());
        // find class supertype
        for (TypeMirror t : superTypes) {
            // ignore interface types
            if (!(t instanceof ClassType)) {
                continue;
            }
            ClassType tt = (ClassType) t;
            if (!tt.isInterface()) {
                return new ThisReference(t);
            }
        }

        throw constructParserException("super", "super class not found");
    }

    /**
     * @param s a String representing an identifier (name expression, no dots in it)
     * @return the receiver of the passed String name
     */
    private static Receiver getIdentifierReceiver(
            String s, ProcessingEnvironment env, TreePath path, FlowExpressionContext context)
            throws FlowExpressionParseException {
        Resolver resolver = new Resolver(env);
        if (!context.parsingMember && s.startsWith("_param_")) {
            return getParameterReceiver(s, context);
        } else if (!context.parsingMember && context.useLocalScope) {
            // Attempt to match a local variable within the scope of the
            // given path before attempting to match a field.
            VariableElement varElem = resolver.findLocalVariableOrParameterOrField(s, path);
            if (varElem != null) {
                if (varElem.getKind() == ElementKind.FIELD) {
                    boolean isOriginalReceiver = context.receiver instanceof ThisReference;
                    return getReceiverField(s, context, isOriginalReceiver, varElem);
                } else {
                    return new LocalVariable(varElem);
                }
            }
        }

        // field access
        TypeMirror receiverType = context.receiver.getType();
        boolean originalReceiver = true;
        VariableElement fieldElem = null;

        if (receiverType.getKind() == TypeKind.ARRAY && s.equals("length")) {
            fieldElem = resolver.findField(s, receiverType, path);
        }

        // Search for field in each enclosing class.
        while (receiverType.getKind() == TypeKind.DECLARED) {
            fieldElem = resolver.findField(s, receiverType, path);
            if (fieldElem != null) {
                break;
            }
            receiverType = getTypeOfEnclosingClass((DeclaredType) receiverType);
            originalReceiver = false;
        }

        if (fieldElem != null && fieldElem.getKind() == ElementKind.FIELD) {
            return getReceiverField(s, context, originalReceiver, fieldElem);
        }

        // Class name
        Element classElem = resolver.findClass(s, path);
        TypeMirror classType = ElementUtils.getType(classElem);
        if (classType != null) {
            return new ClassName(classType);
        }

        MethodTree enclMethod = TreeUtils.enclosingMethod(path);
        if (enclMethod != null) {
            List<? extends VariableTree> params = enclMethod.getParameters();
            for (int i = 0; i < params.size(); i++) {
                if (params.get(i).getName().contentEquals(s)) {
                    throw constructParserException(
                            s,
                            String.format(DependentTypesError.FORMAL_PARAM_NAME_STRING, i + 1, s));
                }
            }
        }

        throw constructParserException(s, "identifier not found");
    }

    private static Receiver getReceiverField(
            String s,
            FlowExpressionContext context,
            boolean originalReceiver,
            VariableElement fieldElem)
            throws FlowExpressionParseException {
        TypeMirror receiverType = context.receiver.getType();

        TypeMirror fieldType = ElementUtils.getType(fieldElem);
        if (ElementUtils.isStatic(fieldElem)) {
            Element classElem = fieldElem.getEnclosingElement();
            Receiver staticClassReceiver = new ClassName(ElementUtils.getType(classElem));
            return new FieldAccess(staticClassReceiver, fieldType, fieldElem);
        }
        Receiver locationOfField;
        if (originalReceiver) {
            locationOfField = context.receiver;
        } else {
            locationOfField =
                    FlowExpressions.internalReprOf(
                            context.checkerContext.getAnnotationProvider(),
                            new ImplicitThisLiteralNode(receiverType));
        }
        if (locationOfField instanceof ClassName) {
            throw constructParserException(
                    s, "a non-static field cannot have a class name as a receiver.");
        }
        return new FieldAccess(locationOfField, fieldType, fieldElem);
    }

    /**
     * @param s A String that starts with "_param_"
     * @return The receiver of the parameter passed
     */
    private static Receiver getParameterReceiver(String s, FlowExpressionContext context)
            throws FlowExpressionParseException {
        if (context.arguments == null) {
            throw constructParserException(s, "no parameter found");
        }
        int idx = Integer.parseInt(s.substring(7)); // "_param_".length() = 7

        if (idx > context.arguments.size()) {
            throw new FlowExpressionParseException(
                    "flowexpr.parse.index.too.big", Integer.toString(idx));
        }
        return context.arguments.get(idx - 1);
    }

    /** @return the receiver of the passed MethodCallExpr */
    private static Receiver getMethodCallReceiver(
            MethodCallExpr expr,
            FlowExpressionContext context,
            TreePath path,
            ProcessingEnvironment env)
            throws FlowExpressionParseException {
        String s = expr.toString();
        Resolver resolver = new Resolver(env);

        // methods with scope, like "item.get()", need special treatment.
        if (expr.getScope().isPresent()) {
            Receiver receiver = getReceiverFromExpression(expr.getScope().get(), context, path);
            FlowExpressionContext newContext =
                    context.copyChangeToParsingMemberOfReceiver(receiver);
            return getReceiverFromExpression(expr.removeScope(), newContext, path);
        }

        String methodName = expr.getNameAsString();

        // parse parameter list
        List<Receiver> parameters = new ArrayList<>();

        for (Expression expression : expr.getArguments()) {
            parameters.add(
                    getReceiverFromExpression(expression, context.copyAndUseOuterReceiver(), path));
        }

        // get types for parameters
        List<TypeMirror> parameterTypes = new ArrayList<>();
        for (Receiver p : parameters) {
            parameterTypes.add(p.getType());
        }
        ExecutableElement methodElement;
        try {
            Element element = null;

            // try to find the correct method
            TypeMirror receiverType = context.receiver.getType();

            if (receiverType.getKind() == TypeKind.ARRAY) {
                element = resolver.findMethod(methodName, receiverType, path, parameterTypes);
            }

            // Search for method in each enclosing class.
            while (receiverType.getKind() == TypeKind.DECLARED) {
                element = resolver.findMethod(methodName, receiverType, path, parameterTypes);
                if (element.getKind() == ElementKind.METHOD) {
                    break;
                }
                receiverType = getTypeOfEnclosingClass((DeclaredType) receiverType);
            }

            if (element == null) {
                throw constructParserException(s, "element==null");
            }
            if (element.getKind() != ElementKind.METHOD) {
                throw constructParserException(s, "element.getKind()==" + element.getKind());
            }

            methodElement = (ExecutableElement) element;

            for (int i = 0; i < parameters.size(); i++) {
                VariableElement formal = methodElement.getParameters().get(i);
                TypeMirror formalType = formal.asType();
                Receiver actual = parameters.get(i);
                TypeMirror actualType = actual.getType();
                // boxing necessary
                if (TypesUtils.isBoxedPrimitive(formalType) && TypesUtils.isPrimitive(actualType)) {
                    MethodSymbol valueOfMethod = TreeBuilder.getValueOfMethod(env, formalType);
                    List<Receiver> p = new ArrayList<>();
                    p.add(actual);
                    Receiver boxedParam =
                            new MethodCall(formalType, valueOfMethod, new ClassName(formalType), p);
                    parameters.set(i, boxedParam);
                }
            }
        } catch (Throwable t) {
            if (t.getMessage() == null) {
                throw new Error("no detail message in " + t.getClass(), t);
            }
            throw constructParserException(s, t.getMessage());
        }

        // TODO: reinstate this test, but issue a warning that the user
        // can override, rather than halting parsing which the user cannot override.
        /*if (!PurityUtils.isDeterministic(context.checkerContext.getAnnotationProvider(),
                methodElement)) {
            throw new FlowExpressionParseException(Result.failure(
                    "flowexpr.method.not.deterministic",
                    methodElement.getSimpleName()));
        }*/
        if (ElementUtils.isStatic(methodElement)) {
            Element classElem = methodElement.getEnclosingElement();
            Receiver staticClassReceiver = new ClassName(ElementUtils.getType(classElem));
            return new MethodCall(
                    ElementUtils.getType(methodElement),
                    methodElement,
                    staticClassReceiver,
                    parameters);
        } else {
            if (context.receiver instanceof ClassName) {
                throw constructParserException(
                        s, "a non-static method call cannot have a class name as a receiver");
            }
            TypeMirror methodType =
                    TypesUtils.substituteMethodReturnType(
                            methodElement, context.receiver.getType(), env);
            return new MethodCall(methodType, methodElement, context.receiver, parameters);
        }
    }

    /** @return the Receiver of the passed ArrayAccessExpr */
    private static Receiver getArrayAccessReceiver(
            ArrayAccessExpr expr, FlowExpressionContext context, TreePath path)
            throws FlowExpressionParseException {

        Receiver receiver = getReceiverFromExpression(expr.getName(), context, path);
        FlowExpressionContext contextForIndex = context.copyAndUseOuterReceiver();
        Receiver index = getReceiverFromExpression(expr.getIndex(), contextForIndex, path);
        TypeMirror receiverType = receiver.getType();
        if (!(receiverType instanceof ArrayType)) {
            throw constructParserException(
                    expr.toString(),
                    String.format("receiver not an array: %s : %s", receiver, receiverType));
        }
        TypeMirror componentType = ((ArrayType) receiverType).getComponentType();
        return new ArrayAccess(componentType, receiver, index);
    }

    /**
     * @return a list of 1-based indices of all formal parameters that occur in {@code s}. Each
     *     formal parameter occurs in s as a string like "#1" or "#4". This routine does not do
     *     proper parsing; for instance, if "#2" appears within a string in s, then 2 would still be
     *     in the result list.
     */
    public static List<Integer> parameterIndices(String s) {
        List<Integer> result = new ArrayList<>();
        Matcher matcher = UNANCHORED_PARAMETER_PATTERN.matcher(s);
        while (matcher.find()) {
            int idx = Integer.parseInt(matcher.group(1));
            result.add(idx);
        }
        return result;
    }

    ///////////////////////////////////////////////////////////////////////////
    /// Contexts
    ///

    /**
     * Context used to parse a flow expression. When parsing flow expression E in annotation
     * {@code @A(E)}, the context is the program element that is annotated by {@code @A(E)}.
     */
    public static class FlowExpressionContext {
        public final Receiver receiver;
        public final List<Receiver> arguments;
        public final Receiver outerReceiver;
        public final BaseContext checkerContext;
        /**
         * Whether or not the FlowExpressionParser is parsing the "member" part of a member select.
         */
        public final boolean parsingMember;
        /** Whether the TreePath should be used to find identifiers. Defaults to true. */
        public final boolean useLocalScope;

        /**
         * Creates context for parsing a flow expression.
         *
         * @param receiver used to replace "this" in a flow expression and used to resolve
         *     identifiers in the flow expression with an implicit "this"
         * @param arguments used to replace parameter references, e.g. #1, in flow expressions, null
         *     if no arguments
         * @param checkerContext used to create {@link
         *     org.checkerframework.dataflow.analysis.FlowExpressions.Receiver}s
         */
        public FlowExpressionContext(
                Receiver receiver, List<Receiver> arguments, BaseContext checkerContext) {
            this(receiver, receiver, arguments, checkerContext);
        }

        private FlowExpressionContext(
                Receiver receiver,
                Receiver outerReceiver,
                List<Receiver> arguments,
                BaseContext checkerContext) {
            this(receiver, outerReceiver, arguments, checkerContext, false, true);
        }

        private FlowExpressionContext(
                Receiver receiver,
                Receiver outerReceiver,
                List<Receiver> arguments,
                BaseContext checkerContext,
                boolean parsingMember,
                boolean useLocalScope) {
            assert checkerContext != null;
            this.receiver = receiver;
            this.arguments = arguments;
            this.outerReceiver = outerReceiver;
            this.checkerContext = checkerContext;
            this.parsingMember = parsingMember;
            this.useLocalScope = useLocalScope;
        }

        /**
         * Creates a {@link FlowExpressionContext} for the method declared in {@code
         * methodDeclaration}.
         *
         * @param methodDeclaration used translate parameter numbers in a flow expression to formal
         *     parameters of the method
         * @param enclosingTree used to look up fields and as type of "this" in flow expressions
         * @param checkerContext use to build FlowExpressions.Receiver
         * @return context created of {@code methodDeclaration}
         */
        public static FlowExpressionContext buildContextForMethodDeclaration(
                MethodTree methodDeclaration, Tree enclosingTree, BaseContext checkerContext) {
            return buildContextForMethodDeclaration(
                    methodDeclaration, TreeUtils.typeOf(enclosingTree), checkerContext);
        }

        /**
         * Creates a {@link FlowExpressionContext} for the method declared in {@code
         * methodDeclaration}.
         *
         * @param methodDeclaration used translate parameter numbers in a flow expression to formal
         *     parameters of the method
         * @param enclosingType used to look up fields and as type of "this" in flow expressions
         * @param checkerContext use to build FlowExpressions.Receiver
         * @return context created of {@code methodDeclaration}
         */
        public static FlowExpressionContext buildContextForMethodDeclaration(
                MethodTree methodDeclaration,
                TypeMirror enclosingType,
                BaseContext checkerContext) {

            Node receiver;
            if (methodDeclaration.getModifiers().getFlags().contains(Modifier.STATIC)) {
                Element classElt =
                        ElementUtils.enclosingClass(
                                TreeUtils.elementFromDeclaration(methodDeclaration));
                receiver = new ClassNameNode(enclosingType, classElt);
            } else {
                receiver = new ImplicitThisLiteralNode(enclosingType);
            }
            Receiver internalReceiver =
                    FlowExpressions.internalReprOf(
                            checkerContext.getAnnotationProvider(), receiver);
            List<Receiver> internalArguments = new ArrayList<>();
            for (VariableTree arg : methodDeclaration.getParameters()) {
                internalArguments.add(
                        FlowExpressions.internalReprOf(
                                checkerContext.getAnnotationProvider(),
                                new LocalVariableNode(arg, receiver)));
            }
            return new FlowExpressionContext(internalReceiver, internalArguments, checkerContext);
        }

        public static FlowExpressionContext buildContextForLambda(
                LambdaExpressionTree lambdaTree, TreePath path, BaseContext checkerContext) {
            TypeMirror enclosingType = TreeUtils.typeOf(TreeUtils.enclosingClass(path));
            Node receiver = new ImplicitThisLiteralNode(enclosingType);
            Receiver internalReceiver =
                    FlowExpressions.internalReprOf(
                            checkerContext.getAnnotationProvider(), receiver);
            List<Receiver> internalArguments = new ArrayList<>();
            for (VariableTree arg : lambdaTree.getParameters()) {
                internalArguments.add(
                        FlowExpressions.internalReprOf(
                                checkerContext.getAnnotationProvider(),
                                new LocalVariableNode(arg, receiver)));
            }
            return new FlowExpressionContext(internalReceiver, internalArguments, checkerContext);
        }

        /**
         * Creates a {@link FlowExpressionContext} for the method declared in {@code
         * methodDeclaration}.
         *
         * @param methodDeclaration used translate parameter numbers in a flow expression to formal
         *     parameters of the method
         * @param currentPath to find the enclosing class, which is used to look up fields and as
         *     type of "this" in flow expressions
         * @param checkerContext use to build FlowExpressions.Receiver
         * @return context created of {@code methodDeclaration}
         */
        public static FlowExpressionContext buildContextForMethodDeclaration(
                MethodTree methodDeclaration, TreePath currentPath, BaseContext checkerContext) {
            Tree classTree = TreeUtils.enclosingClass(currentPath);
            return buildContextForMethodDeclaration(methodDeclaration, classTree, checkerContext);
        }

        /**
         * @return a {@link FlowExpressionContext} for the class {@code classTree} as seen at the
         *     class declaration.
         */
        public static FlowExpressionContext buildContextForClassDeclaration(
                ClassTree classTree, BaseContext checkerContext) {
            Node receiver = new ImplicitThisLiteralNode(TreeUtils.typeOf(classTree));

            Receiver internalReceiver =
                    FlowExpressions.internalReprOf(
                            checkerContext.getAnnotationProvider(), receiver);
            List<Receiver> internalArguments = new ArrayList<>();
            return new FlowExpressionContext(internalReceiver, internalArguments, checkerContext);
        }

        /**
         * @return a {@link FlowExpressionContext} for the method {@code methodInvocation}
         *     (represented as a {@link Node} as seen at the method use (i.e., at a method call
         *     site).
         */
        public static FlowExpressionContext buildContextForMethodUse(
                MethodInvocationNode methodInvocation, BaseContext checkerContext) {
            Node receiver = methodInvocation.getTarget().getReceiver();
            Receiver internalReceiver =
                    FlowExpressions.internalReprOf(
                            checkerContext.getAnnotationProvider(), receiver);
            List<Receiver> internalArguments = new ArrayList<>();
            for (Node arg : methodInvocation.getArguments()) {
                internalArguments.add(
                        FlowExpressions.internalReprOf(
                                checkerContext.getAnnotationProvider(), arg));
            }
            return new FlowExpressionContext(internalReceiver, internalArguments, checkerContext);
        }

        /**
         * @return a {@link FlowExpressionContext} for the method {@code methodInvocation}
         *     (represented as a {@link MethodInvocationTree} as seen at the method use (i.e., at a
         *     method call site).
         */
        public static FlowExpressionContext buildContextForMethodUse(
                MethodInvocationTree methodInvocation, BaseContext checkerContext) {
            ExpressionTree receiverTree = TreeUtils.getReceiverTree(methodInvocation);
            FlowExpressions.Receiver receiver;
            if (receiverTree == null) {
                receiver =
                        FlowExpressions.internalReprOfImplicitReceiver(
                                TreeUtils.elementFromUse(methodInvocation));
            } else {
                receiver =
                        FlowExpressions.internalReprOf(
                                checkerContext.getAnnotationProvider(), receiverTree);
            }

            List<? extends ExpressionTree> args = methodInvocation.getArguments();
            List<FlowExpressions.Receiver> argReceivers = new ArrayList<>(args.size());
            for (ExpressionTree argTree : args) {
                argReceivers.add(
                        FlowExpressions.internalReprOf(
                                checkerContext.getAnnotationProvider(), argTree));
            }

            return new FlowExpressionContext(receiver, argReceivers, checkerContext);
        }

        /**
         * @return a {@link FlowExpressionContext} for the constructor {@code n} (represented as a
         *     {@link Node} as seen at the method use (i.e., at a method call site).
         */
        public static FlowExpressionContext buildContextForNewClassUse(
                ObjectCreationNode n, BaseContext checkerContext) {

            // This returns an FlowExpressions.Unknown with the type set to the class in which the
            // constructor is declared
            Receiver internalReceiver =
                    FlowExpressions.internalReprOf(checkerContext.getAnnotationProvider(), n);

            List<Receiver> internalArguments = new ArrayList<>();
            for (Node arg : n.getArguments()) {
                internalArguments.add(
                        FlowExpressions.internalReprOf(
                                checkerContext.getAnnotationProvider(), arg));
            }

            return new FlowExpressionContext(internalReceiver, internalArguments, checkerContext);
        }

        /**
         * Returns a copy of the context that differs in that it has a different receiver and
         * parsingMember is set to true. The outer receiver remains unchanged.
         */
        public FlowExpressionContext copyChangeToParsingMemberOfReceiver(Receiver receiver) {
            return new FlowExpressionContext(
                    receiver,
                    outerReceiver,
                    arguments,
                    checkerContext,
                    /*parsingMember=*/ true,
                    useLocalScope);
        }

        /**
         * Returns a copy of the context that differs in that it uses the outer receiver as main
         * receiver (and also retains it as the outer receiver), and parsingMember is set to false.
         */
        public FlowExpressionContext copyAndUseOuterReceiver() {
            return new FlowExpressionContext(
                    outerReceiver, // NOTE different than in this object
                    outerReceiver,
                    arguments,
                    checkerContext,
                    /*parsingMember=*/ false,
                    useLocalScope);
        }

        /**
         * Returns a copy of the context that differs in that useLocalScope is set to the given
         * value.
         */
        public FlowExpressionContext copyAndSetUseLocalScope(boolean useLocalScope) {
            return new FlowExpressionContext(
                    receiver,
                    outerReceiver,
                    arguments,
                    checkerContext,
                    parsingMember,
                    useLocalScope);
        }
    }

    /**
     * Returns the type of the inner most enclosing class.Type.noType is returned if no enclosing
     * class is found. This is in contrast to {@link DeclaredType#getEnclosingType()} which returns
     * the type of the inner most instance. If the inner most enclosing class is static this method
     * will return the type of that class where as {@link DeclaredType#getEnclosingType()} will
     * return the type of the inner most enclosing class that is not static.
     *
     * @param type a DeclaredType
     * @return the type of the innermost enclosing class or Type.noType
     */
    private static TypeMirror getTypeOfEnclosingClass(DeclaredType type) {
        if (type instanceof ClassType) {
            // enclClass() needs to be called on tsym.owner,
            // otherwise it simply returns tsym.
            Symbol sym = ((ClassType) type).tsym.owner;

            if (sym == null) {
                return Type.noType;
            }

            ClassSymbol cs = sym.enclClass();

            if (cs == null) {
                return Type.noType;
            }

            return cs.asType();
        } else {
            return type.getEnclosingType();
        }
    }

    public static Receiver internalReprOfVariable(AnnotatedTypeFactory provider, VariableTree tree)
            throws FlowExpressionParseException {
        Element elt = TreeUtils.elementFromDeclaration(tree);

        if (elt.getKind() == ElementKind.LOCAL_VARIABLE
                || elt.getKind() == ElementKind.RESOURCE_VARIABLE
                || elt.getKind() == ElementKind.EXCEPTION_PARAMETER
                || elt.getKind() == ElementKind.PARAMETER) {
            return new LocalVariable(elt);
        }
        Receiver receiverF = FlowExpressions.internalReprOfImplicitReceiver(elt);
        FlowExpressionContext context =
                new FlowExpressionContext(receiverF, null, provider.getContext());
        return parse(tree.getName().toString(), context, provider.getPath(tree), false);
    }

    ///////////////////////////////////////////////////////////////////////////
    /// Exceptions
    ///

    /**
     * An exception that indicates a parse error. Call {@link #getResult} to obtain a {@link Result}
     * that can be used for error reporting.
     */
    public static class FlowExpressionParseException extends Exception {
        private static final long serialVersionUID = 2L;
        private @CompilerMessageKey String errorKey;
        public final Object[] args;

        public FlowExpressionParseException(@CompilerMessageKey String errorKey, Object... args) {
            this(null, errorKey, args);
        }

        public FlowExpressionParseException(
                Throwable cause, @CompilerMessageKey String errorKey, Object... args) {
            super(cause);
            this.errorKey = errorKey;
            this.args = args;
        }

        @Override
        public String getMessage() {
            return errorKey + " " + Arrays.toString(args);
        }

        /** Return a Result that can be used for error reporting. */
        public Result getResult() {
            return Result.failure(errorKey, args);
        }

        public boolean isFlowParseError() {
            return errorKey.endsWith("flowexpr.parse.error");
        }
    }

    /**
     * Returns a {@link FlowExpressionParseException} for the expression {@code expr} with
     * explanation {@code explanation}.
     */
    private static FlowExpressionParseException constructParserException(
            String expr, String explanation) {
        if (expr == null) {
            throw new Error("Must have an expression.");
        }
        if (explanation == null) {
            throw new Error("Must have an explanation.");
        }
        return new FlowExpressionParseException(
                (Throwable) null,
                "flowexpr.parse.error",
                "Invalid '" + expr + "' because " + explanation);
    }
}
