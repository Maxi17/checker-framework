package org.checkerframework.common.purity;

import java.util.LinkedHashSet;
import org.checkerframework.common.basetype.BaseTypeChecker;

/**
 * Perform purity checking only.
 *
 * @checker_framework.manual #type-refinement-purity Side effects, determinism, purity, and
 *     flow-sensitive analysis
 */
public class PurityChecker extends BaseTypeChecker {

    @Override
    protected LinkedHashSet<Class<? extends BaseTypeChecker>> getImmediateSubcheckerClasses() {
        return new LinkedHashSet<>();
    }
}
