package com.example.myapp.core.di

import javax.inject.Qualifier

@Qualifier
@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier
@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

@Qualifier
@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
