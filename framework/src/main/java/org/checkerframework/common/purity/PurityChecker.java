package org.checkerframework.common.purity;

import java.util.LinkedHashSet;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.basetype.BaseTypeVisitor;

/**
 * Perform purity checking only.
 *
 * @checker_framework.manual #type-refinement-purity Side effects, determinism, purity, and
 *     flow-sensitive analysis
 */
public class PurityChecker extends BaseTypeChecker {

    @Override
    protected BaseTypeVisitor<?> createSourceVisitor() {
        return new PurityVisitor(this);
    }

    // This override is necessary because BaseTypeChecker can add PurityChecker as a subchecker if
    // the command line argument is present. Without this, a StackOverflow error would be issued
    // because the PurityChecker will try to add itself as a subchecker.
    @Override
    protected LinkedHashSet<Class<? extends BaseTypeChecker>> getImmediateSubcheckerClasses() {
        return new LinkedHashSet<>();
    }
}
