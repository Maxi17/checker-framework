package org.checkerframework.common.purity;

import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.basetype.BaseTypeVisitor;

public class PurityVisitor extends BaseTypeVisitor<PurityAnnotatedTypeFactory> {

    public PurityVisitor(BaseTypeChecker checker) {
        super(checker);
    }
}
