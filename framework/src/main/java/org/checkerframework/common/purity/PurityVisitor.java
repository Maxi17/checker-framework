package org.checkerframework.common.purity;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.type.TypeKind;
import org.checkerframework.checker.compilermsgs.qual.CompilerMessageKey;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.basetype.BaseTypeVisitor;
import org.checkerframework.dataflow.qual.Deterministic;
import org.checkerframework.dataflow.qual.Pure;
import org.checkerframework.dataflow.qual.Pure.Kind;
import org.checkerframework.dataflow.qual.SideEffectFree;
import org.checkerframework.dataflow.util.PurityUtils;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.javacutil.AnnotationProvider;
import org.checkerframework.javacutil.Pair;
import org.checkerframework.javacutil.TreeUtils;

/**
 * A visitor that determines the purity (as defined by {@link
 * org.checkerframework.dataflow.qual.SideEffectFree}, {@link
 * org.checkerframework.dataflow.qual.Deterministic}, and {@link
 * org.checkerframework.dataflow.qual.Pure}) of a statement or expression.
 *
 * @see SideEffectFree
 * @see Deterministic
 * @see Pure
 */
public class PurityVisitor extends BaseTypeVisitor<PurityAnnotatedTypeFactory> {

    /** Whether -AsuggestPureMethods was supplied. */
    private boolean suggestPureMethods;

    /** Whether -AcheckPurityAnnotations was supplied. */
    private boolean checkPurityAnnotations;

    /** Whether -AassumeSideEffectFree was supplied. */
    private boolean assumeSideEffectFree;

    /** Whether -AassumeDeterministic was supplied. */
    private boolean assumeDeterministic;

    /**
     * Create a PurityVisitor associated with the given checker.
     *
     * @param checker the checker
     */
    public PurityVisitor(BaseTypeChecker checker) {
        super(checker);
        suggestPureMethods = checker.hasOption("suggestPureMethods");
        checkPurityAnnotations = checker.hasOption("checkPurityAnnotations");
        assumeSideEffectFree = checker.hasOption("assumeSideEffectFree");
        assumeDeterministic = checker.hasOption("assumeDeterministic");
    }

    /**
     * Compute whether the given statement is side-effect-free, deterministic, or both. Returns a
     * result that can be queried.
     *
     * @param statement the statement to check
     * @param annoProvider the annotation provider
     * @param assumeSideEffectFree true if all methods should be assumed to be @SideEffectFree
     * @param assumeDeterministic true if all methods should be assumed to be @Deterministic
     * @return information about whether the given statement is side-effect-free, deterministic, or
     *     both
     */
    private PurityResult checkPurity(
            TreePath statement,
            AnnotationProvider annoProvider,
            boolean assumeSideEffectFree,
            boolean assumeDeterministic) {
        PurityVisitorHelper helper =
                new PurityVisitorHelper(annoProvider, assumeSideEffectFree, assumeDeterministic);
        helper.scan(statement, null);
        return helper.purityResult;
    }

    /**
     * Result of the {@link PurityVisitorHelper}. Can be queried regarding whether a given tree was
     * side-effect-free, deterministic, or both; also gives reasons if the answer is "no".
     */
    public static class PurityResult {

        /** Reasons that the referenced method is not side-effect-free. */
        protected final List<Pair<Tree, String>> notSEFreeReasons = new ArrayList<>(1);

        /** Reasons that the referenced method is not deterministic. */
        protected final List<Pair<Tree, String>> notDetReasons = new ArrayList<>(1);

        /** Reasons that the referenced method is not side-effect-free and deterministic. */
        protected final List<Pair<Tree, String>> notBothReasons = new ArrayList<>(1);

        /**
         * Contains all the varieties of purity that the expression has. Starts out with all
         * varieties, and elements are removed from it as violations are found.
         */
        protected EnumSet<Pure.Kind> kinds = EnumSet.allOf(Pure.Kind.class);

        /**
         * Return the kinds of purity that the method has.
         *
         * @return the kinds of purity that the method has
         */
        public EnumSet<Pure.Kind> getKinds() {
            return kinds;
        }

        /**
         * Is the method pure w.r.t. a given set of kinds?
         *
         * @param otherKinds the varieties of purity to check
         * @return true if the method is pure with respect to all the given kinds
         */
        public boolean isPure(EnumSet<Pure.Kind> otherKinds) {
            return kinds.containsAll(otherKinds);
        }

        /**
         * Get the reasons why the method is not side-effect-free.
         *
         * @return the reasons why the method is not side-effect-free
         */
        public List<Pair<Tree, String>> getNotSEFreeReasons() {
            return notSEFreeReasons;
        }

        /**
         * Add a reason why the method is not side-effect-free.
         *
         * @param t a tree
         * @param msgId why the tree is not side-effect-free
         */
        public void addNotSEFreeReason(Tree t, String msgId) {
            notSEFreeReasons.add(Pair.of(t, msgId));
            kinds.remove(Kind.SIDE_EFFECT_FREE);
        }

        /**
         * Get the reasons why the method is not deterministic.
         *
         * @return the reasons why the method is not deterministic
         */
        public List<Pair<Tree, String>> getNotDetReasons() {
            return notDetReasons;
        }

        /**
         * Add a reason why the method is not deterministic.
         *
         * @param t a tree
         * @param msgId why the tree is not deterministic
         */
        public void addNotDetReason(Tree t, String msgId) {
            notDetReasons.add(Pair.of(t, msgId));
            kinds.remove(Kind.DETERMINISTIC);
        }

        /**
         * Get the reasons why the method is not both side-effect-free and deterministic.
         *
         * @return the reasons why the method is not both side-effect-free and deterministic
         */
        public List<Pair<Tree, String>> getNotBothReasons() {
            return notBothReasons;
        }

        /**
         * Add a reason why the method is not both side-effect-free and deterministic.
         *
         * @param t tree
         * @param msgId why the tree is not deterministic and side-effect-free
         */
        public void addNotBothReason(Tree t, String msgId) {
            notBothReasons.add(Pair.of(t, msgId));
            kinds.remove(Kind.DETERMINISTIC);
            kinds.remove(Kind.SIDE_EFFECT_FREE);
        }
    }

    // TODO: It would be possible to improve efficiency by visiting fewer nodes.  This would require
    // overriding more visit* methods.  I'm not sure whether such an optimization would be worth it.

    /** Helper class. */
    protected static class PurityVisitorHelper extends TreePathScanner<Void, Void> {

        /** The result of the analysis. */
        PurityVisitor.PurityResult purityResult = new PurityVisitor.PurityResult();

        /** Helps performing the analysis. */
        protected final AnnotationProvider annoProvider;

        /**
         * True if all methods should be assumed to be @SideEffectFree, for the purposes of
         * org.checkerframework.dataflow analysis.
         */
        private final boolean assumeSideEffectFree;

        /**
         * True if all methods should be assumed to be @Deterministic, for the purposes of
         * org.checkerframework.dataflow analysis.
         */
        private final boolean assumeDeterministic;

        /**
         * Create a PurityVisitorHelper.
         *
         * @param annoProvider the annotation provider
         * @param assumeSideEffectFree true if all methods should be assumed to be @SideEffectFree
         * @param assumeDeterministic true if all methods should be assumed to be @Deterministic
         */
        public PurityVisitorHelper(
                AnnotationProvider annoProvider,
                boolean assumeSideEffectFree,
                boolean assumeDeterministic) {
            this.annoProvider = annoProvider;
            this.assumeSideEffectFree = assumeSideEffectFree;
            this.assumeDeterministic = assumeDeterministic;
        }

        @Override
        public Void visitCatch(CatchTree node, Void ignore) {
            purityResult.addNotDetReason(node, "catch");
            return super.visitCatch(node, ignore);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void ignore) {
            assert TreeUtils.isUseOfElement(node) : "@AssumeAssertion(nullness): tree kind";
            Element elt = TreeUtils.elementFromUse(node);
            if (!PurityUtils.hasPurityAnnotation(annoProvider, elt)) {
                purityResult.addNotBothReason(node, "call.method");
            } else {
                EnumSet<Pure.Kind> purityKinds =
                        (assumeDeterministic && assumeSideEffectFree)
                                // Avoid computation if not necessary
                                ? EnumSet.of(Kind.DETERMINISTIC, Kind.SIDE_EFFECT_FREE)
                                : PurityUtils.getPurityKinds(annoProvider, elt);
                boolean det = assumeDeterministic || purityKinds.contains(Kind.DETERMINISTIC);
                boolean seFree =
                        assumeSideEffectFree || purityKinds.contains(Kind.SIDE_EFFECT_FREE);
                if (!det && !seFree) {
                    purityResult.addNotBothReason(node, "call.method");
                } else if (!det) {
                    purityResult.addNotDetReason(node, "call.method");
                } else if (!seFree) {
                    purityResult.addNotSEFreeReason(node, "call.method");
                }
            }
            return super.visitMethodInvocation(node, ignore);
        }

        @Override
        public Void visitNewClass(NewClassTree node, Void ignore) {
            // Ordinarily, "new MyClass()" is forbidden.  It is permitted, however, when it is the
            // expression in "throw EXPR;".  (In the future, more expressions could be permitted.)
            //
            // The expression in "throw EXPR;" is allowed to be non-@Deterministic, so long as it is
            // not within a catch block that could catch an exception that the statement throws.
            // For example, EXPR can be object creation (a "new" expression) or can call a
            // non-deterministic method.
            //
            // Coarse rule (currently implemented):
            //  * permit only "throw new SomeExpression(args)", where the constructor is
            //    @SideEffectFree and the args are pure, and forbid all enclosing try statements
            //    that have a catch clause.
            // More precise rule:
            //  * permit other non-deterministic expresssions within throw (at which time move this
            //    logic to visitThrow()).
            //  * the only bad try statements are those with a catch block that is:
            //     * unchecked exceptions
            //        * checked = Exception or lower, but excluding RuntimeException and its
            //          subclasses
            //     * super- or sub-classes of the type of _expr_
            //        * if _expr_ is exactly "new SomeException", this can be changed to just
            //          "superclasses of SomeException".
            //     * super- or sub-classes of exceptions declared to be thrown by any component of
            //       _expr_.
            //     * need to check every containing try statement, not just the nearest enclosing
            //       one.

            // Object creation is usually prohibited, but permit "throw new SomeException();"
            // if it is not contained within any try statement that has a catch clause.
            // (There is no need to check the latter condition, because the Purity Checker
            // forbids all catch statements.)
            Tree parent = getCurrentPath().getParentPath().getLeaf();
            boolean okThrowDeterministic = parent.getKind() == Tree.Kind.THROW;

            assert TreeUtils.isUseOfElement(node) : "@AssumeAssertion(nullness): tree kind";
            Element ctorElement = TreeUtils.elementFromUse(node);
            boolean deterministic = assumeDeterministic || okThrowDeterministic;
            boolean sideEffectFree =
                    assumeSideEffectFree || PurityUtils.isSideEffectFree(annoProvider, ctorElement);
            // This does not use "addNotBothReason" because the reasons are different:  one is
            // because the constructor is called at all, and the other is because the constuctor
            // is not side-effect-free.
            if (!deterministic) {
                purityResult.addNotDetReason(node, "object.creation");
            }
            if (!sideEffectFree) {
                purityResult.addNotSEFreeReason(node, "call.constructor");
            }

            // TODO: if okThrowDeterministic, permit arguments to the newClass to be
            // non-deterministic (don't add those to purityResult), but still don't permit them to
            // have side effects.  This should probably wait until a rewrite of the Purity Checker.
            return super.visitNewClass(node, ignore);
        }

        @Override
        public Void visitAssignment(AssignmentTree node, Void ignore) {
            ExpressionTree variable = node.getVariable();
            assignmentCheck(variable);
            return super.visitAssignment(node, ignore);
        }

        /**
         * Check whether {@code variable} is permitted on the left-hand-side of an assignment.
         *
         * @param variable the lhs to check
         */
        protected void assignmentCheck(ExpressionTree variable) {
            if (TreeUtils.isFieldAccess(variable)) {
                // lhs is a field access
                purityResult.addNotBothReason(variable, "assign.field");
            } else if (variable instanceof ArrayAccessTree) {
                // lhs is array access
                purityResult.addNotBothReason(variable, "assign.array");
            } else {
                // lhs is a local variable
                assert isLocalVariable(variable);
            }
        }

        /**
         * Checks if the argument is a local variable.
         *
         * @param variable the tree to check
         * @return true if the argument is a local variable
         */
        protected boolean isLocalVariable(ExpressionTree variable) {
            return variable instanceof IdentifierTree && !TreeUtils.isFieldAccess(variable);
        }

        @Override
        public Void visitCompoundAssignment(CompoundAssignmentTree node, Void ignore) {
            ExpressionTree variable = node.getVariable();
            assignmentCheck(variable);
            return super.visitCompoundAssignment(node, ignore);
        }
    }

    @Override
    public Void visitMethod(MethodTree node, Void p) {
        boolean anyPurityAnnotation = PurityUtils.hasPurityAnnotation(atypeFactory, node);

        if (checkPurityAnnotations && (anyPurityAnnotation || suggestPureMethods)) {
            // check "no" purity
            EnumSet<Pure.Kind> kinds = PurityUtils.getPurityKinds(atypeFactory, node);
            // @Deterministic makes no sense for a void method or constructor
            boolean isDeterministic = kinds.contains(Kind.DETERMINISTIC);
            if (isDeterministic) {
                if (TreeUtils.isConstructor(node)) {
                    checker.reportWarning(node, "purity.deterministic.constructor");
                } else if (TreeUtils.typeOf(node.getReturnType()).getKind() == TypeKind.VOID) {
                    checker.reportWarning(node, "purity.deterministic.void.method");
                }
            }

            TreePath body = atypeFactory.getPath(node.getBody());
            PurityResult r;
            if (body == null) {
                r = new PurityResult();
            } else {
                r =
                        checkPurity(
                                body,
                                atypeFactory,
                                assumeSideEffectFree || checker.hasOption("assumePure"),
                                assumeDeterministic || checker.hasOption("assumePure"));
            }

            if (!r.isPure(kinds)) {
                reportPurityErrors(r, kinds);
            }

            if (suggestPureMethods) {
                // Issue a warning if the method is pure, but not annotated as such.
                EnumSet<Pure.Kind> additionalKinds = r.getKinds().clone();
                additionalKinds.removeAll(kinds);
                if (TreeUtils.isConstructor(node)) {
                    additionalKinds.remove(Kind.DETERMINISTIC);
                }
                if (!additionalKinds.isEmpty()) {
                    if (additionalKinds.size() == 2) {
                        checker.reportWarning(node, "purity.more.pure", node.getName());
                    } else if (additionalKinds.contains(Kind.SIDE_EFFECT_FREE)) {
                        checker.reportWarning(node, "purity.more.sideeffectfree", node.getName());
                    } else if (additionalKinds.contains(Kind.DETERMINISTIC)) {
                        checker.reportWarning(node, "purity.more.deterministic", node.getName());
                    } else {
                        assert false : "BaseTypeVisitor reached undesirable state";
                    }
                }
            }
        }
        return super.visitMethod(node, p);
    }

    /**
     * Reports errors found during purity checking.
     *
     * @param result the result after purity checking
     * @param expectedKinds expected results
     */
    protected void reportPurityErrors(PurityResult result, EnumSet<Pure.Kind> expectedKinds) {
        assert !result.isPure(expectedKinds);
        EnumSet<Pure.Kind> violations = EnumSet.copyOf(expectedKinds);
        violations.removeAll(result.getKinds());
        if (violations.contains(Kind.DETERMINISTIC) || violations.contains(Kind.SIDE_EFFECT_FREE)) {
            String msgKeyPrefix;
            if (!violations.contains(Kind.SIDE_EFFECT_FREE)) {
                msgKeyPrefix = "purity.not.deterministic.";
            } else if (!violations.contains(Kind.DETERMINISTIC)) {
                msgKeyPrefix = "purity.not.sideeffectfree.";
            } else {
                msgKeyPrefix = "purity.not.deterministic.not.sideeffectfree.";
            }
            for (Pair<Tree, String> r : result.getNotBothReasons()) {
                reportPurityError(msgKeyPrefix, r);
            }
            if (violations.contains(Kind.SIDE_EFFECT_FREE)) {
                for (Pair<Tree, String> r : result.getNotSEFreeReasons()) {
                    reportPurityError("purity.not.sideeffectfree.", r);
                }
            }
            if (violations.contains(Kind.DETERMINISTIC)) {
                for (Pair<Tree, String> r : result.getNotDetReasons()) {
                    reportPurityError("purity.not.deterministic.", r);
                }
            }
        }
    }

    /**
     * Reports a single purity error.
     *
     * @param msgKeyPrefix the prefix of the message key to use when reporting
     * @param r the result to report
     */
    private void reportPurityError(String msgKeyPrefix, Pair<Tree, String> r) {
        String reason = r.second;
        @SuppressWarnings("CompilerMessages")
        @CompilerMessageKey String msgKey = msgKeyPrefix + reason;
        if (reason.equals("call")) {
            MethodInvocationTree mitree = (MethodInvocationTree) r.first;
            checker.reportError(r.first, msgKey, mitree.getMethodSelect());
        } else {
            checker.reportError(r.first, msgKey);
        }
    }

    @Override
    protected OverrideChecker createOverrideChecker(
            Tree overriderTree,
            AnnotatedTypeMirror.AnnotatedExecutableType overrider,
            AnnotatedTypeMirror overridingType,
            AnnotatedTypeMirror overridingReturnType,
            AnnotatedTypeMirror.AnnotatedExecutableType overridden,
            AnnotatedTypeMirror.AnnotatedDeclaredType overriddenType,
            AnnotatedTypeMirror overriddenReturnType) {
        return new PurityOverrideChecker(
                overriderTree,
                overrider,
                overridingType,
                overridingReturnType,
                overridden,
                overriddenType,
                overriddenReturnType);
    }

    /** This class adds a purity check to the OverrideChecker. */
    protected class PurityOverrideChecker extends OverrideChecker {

        /**
         * Creates a PurityOverrideChecker.
         *
         * @param overriderTree the AST node of the overriding method or method reference
         * @param overrider the type of the overriding method
         * @param overridingType the type enclosing the overrider method, usually an
         *     AnnotatedDeclaredType; for Method References may be something else
         * @param overridingReturnType the return type of the overriding method
         * @param overridden the type of the overridden method
         * @param overriddenType the declared type enclosing the overridden method
         * @param overriddenReturnType the return type of the overridden method
         */
        public PurityOverrideChecker(
                Tree overriderTree,
                AnnotatedTypeMirror.AnnotatedExecutableType overrider,
                AnnotatedTypeMirror overridingType,
                AnnotatedTypeMirror overridingReturnType,
                AnnotatedTypeMirror.AnnotatedExecutableType overridden,
                AnnotatedTypeMirror.AnnotatedDeclaredType overriddenType,
                AnnotatedTypeMirror overriddenReturnType) {
            super(
                    overriderTree,
                    overrider,
                    overridingType,
                    overridingReturnType,
                    overridden,
                    overriddenType,
                    overriddenReturnType);
        }

        @Override
        public boolean checkOverride() {
            if (checker.shouldSkipUses(overriddenType.getUnderlyingType().asElement())) {
                return true;
            }

            checkOverridePurity();
            return super.checkOverride();
        }

        /**
         * Checks if the override is valid according to the Purity Checker and reports the errors if
         * there are any.
         */
        private void checkOverridePurity() {
            String msgKey =
                    methodReference ? "purity.invalid.methodref" : "purity.invalid.overriding";

            // check purity annotations
            EnumSet<Pure.Kind> superPurity =
                    PurityUtils.getPurityKinds(atypeFactory, overridden.getElement());
            EnumSet<Pure.Kind> subPurity =
                    PurityUtils.getPurityKinds(atypeFactory, overrider.getElement());
            if (!subPurity.containsAll(superPurity)) {
                checker.reportError(
                        overriderTree,
                        msgKey,
                        overriderMeth,
                        overriderTyp,
                        overriddenMeth,
                        overriddenTyp,
                        subPurity,
                        superPurity);
            }
        }
    }
}