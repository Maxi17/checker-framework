package org.checkerframework.common.purity;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.checkerframework.common.basetype.BaseAnnotatedTypeFactory;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.purity.qual.Deterministic;
import org.checkerframework.common.purity.qual.Pure;
import org.checkerframework.common.purity.qual.PurityUnqualified;
import org.checkerframework.common.purity.qual.SideEffectFree;
import org.checkerframework.common.purity.qual.TerminatesExecution;
import org.checkerframework.javacutil.AnnotationBuilder;

/** The Purity Checker is used to determine if methods are deterministic and side-effect-free. */
public class PurityAnnotatedTypeFactory extends BaseAnnotatedTypeFactory {

    public PurityAnnotatedTypeFactory(BaseTypeChecker checker) {
        super(checker);

        addAliasedAnnotation(
                Pure.class,
                AnnotationBuilder.fromClass(
                        getElementUtils(), org.checkerframework.dataflow.qual.Pure.class));
        addAliasedAnnotation(
                Deterministic.class,
                AnnotationBuilder.fromClass(
                        getElementUtils(), org.checkerframework.dataflow.qual.Deterministic.class));
        addAliasedAnnotation(
                SideEffectFree.class,
                AnnotationBuilder.fromClass(
                        getElementUtils(),
                        org.checkerframework.dataflow.qual.SideEffectFree.class));
        addAliasedAnnotation(
                TerminatesExecution.class,
                AnnotationBuilder.fromClass(
                        getElementUtils(),
                        org.checkerframework.dataflow.qual.TerminatesExecution.class));

        this.postInit();
    }

    @Override
    protected Set<Class<? extends Annotation>> createSupportedTypeQualifiers() {
        return new HashSet<>(
                Arrays.asList(
                        PurityUnqualified.class,
                        Pure.class,
                        Deterministic.class,
                        SideEffectFree.class,
                        TerminatesExecution.class));
    }
}
