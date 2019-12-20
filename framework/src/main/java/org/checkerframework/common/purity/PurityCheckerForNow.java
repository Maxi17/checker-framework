package org.checkerframework.common.purity;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePathScanner;
import javax.lang.model.element.Element;
import org.checkerframework.dataflow.qual.Deterministic;
import org.checkerframework.dataflow.qual.Pure;
import org.checkerframework.dataflow.qual.SideEffectFree;
import org.checkerframework.dataflow.util.PurityUtils;
import org.checkerframework.javacutil.AnnotationProvider;
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
public class PurityCheckerForNow {

    // TODO: It would be possible to improve efficiency by visiting fewer nodes.  This would require
    // overriding more visit* methods.  I'm not sure whether such an optimization would be worth it.

    /** Helper class to keep {@link PurityCheckerForNow}'s interface clean. */
    protected static class PurityCheckerHelper extends TreePathScanner<Void, Void> {

        PurityVisitor.PurityResult purityResult = new PurityVisitor.PurityResult();

        protected final AnnotationProvider annoProvider;

        /**
         * True if all methods should be assumed to be @SideEffectFree, for the purposes of
         * org.checkerframework.dataflow analysis.
         */
        private final boolean assumeSideEffectFree;

        public PurityCheckerHelper(AnnotationProvider annoProvider, boolean assumeSideEffectFree) {
            this.annoProvider = annoProvider;
            this.assumeSideEffectFree = assumeSideEffectFree;
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
                boolean det = PurityUtils.isDeterministic(annoProvider, elt);
                boolean seFree =
                        (assumeSideEffectFree || PurityUtils.isSideEffectFree(annoProvider, elt));
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
            boolean deterministic = okThrowDeterministic;
            boolean sideEffectFree =
                    (assumeSideEffectFree
                            || PurityUtils.isSideEffectFree(annoProvider, ctorElement));
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

        /** Default documentation. */
        protected void assignmentCheck(ExpressionTree variable) {
            if (TreeUtils.isFieldAccess(variable)) {
                // rhs is a field access
                purityResult.addNotBothReason(variable, "assign.field");
            } else if (variable instanceof ArrayAccessTree) {
                // rhs is array access
                purityResult.addNotBothReason(variable, "assign.array");
            } else {
                // rhs is a local variable
                assert isLocalVariable(variable);
            }
        }

        /** Default documentation. */
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
}
