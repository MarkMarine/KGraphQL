package com.github.pgutkowski.kgraphql.schema.impl

import com.github.pgutkowski.kgraphql.schema.model.*
import com.github.pgutkowski.kgraphql.typeName
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.jvmErasure


class SchemaStructureBuilder(
        val queries: List<KQLQuery<*>>,
        val mutations: List<KQLMutation<*>>,
        val objects: List<KQLType.Object<*>>,
        val scalars: List<KQLType.Scalar<*>>,
        val enums: List<KQLType.Enumeration<*>>,
        val unions: List<KQLType.Union>
) {

    /**
     * MutableType allows to avoid circular reference issues when building schema.
     * @see getType
     * @see typeCache
     */
    private class MutableType(
            val kqlObjectType: KQLType.Object<*>,
            val mutableProperties: MutableMap<String, SchemaNode.Property> = mutableMapOf(),
            val mutableUnionProperties: MutableMap<String, SchemaNode.UnionProperty> = mutableMapOf()
    ) : SchemaNode.Type(kqlObjectType, mutableProperties, mutableUnionProperties)

    private val typeCache = mutableMapOf<KType, MutableType>()

    fun build() : SchemaStructure {
        val queryNodes = mutableMapOf<String, SchemaNode.Query<*>>()
        val mutationNodes = mutableMapOf<String, SchemaNode.Mutation<*>>()

        queries.map { SchemaNode.Query(it, handleOperation(it)) }
                .associateTo(queryNodes) {it.kqlQuery.name to it}

        mutations.map { SchemaNode.Mutation(it, handleOperation(it))}
                .associateTo(mutationNodes) {it.kqlMutation.name to it}

        return SchemaStructure(queryNodes, mutationNodes, typeCache)
    }

    private fun <R> handleOperation(operation : BaseKQLOperation<R>) : SchemaNode.ReturnType {
        return handleReturnType(operation.kFunction.returnType)
    }

    private fun handleReturnType(kType: KType) : SchemaNode.ReturnType {
        val kClass = kType.jvmErasure
        val isCollection: Boolean = kClass.isSubclassOf(Collection::class)
        if(isCollection){
            return handleCollectionReturnType(collectionKType = kType, entryKType = kType.arguments.first().type!!)
        } else {
            val isNullable: Boolean = kType.isMarkedNullable
            val type = getType(kClass, kType)
            return SchemaNode.ReturnType(type, false, isNullable, false)
        }
    }

    private fun handleCollectionReturnType(collectionKType: KType, entryKType: KType) : SchemaNode.ReturnType {
        val isNullable: Boolean = collectionKType.isMarkedNullable
        val areEntriesNullable : Boolean = entryKType.isMarkedNullable

        val entryKClass = entryKType.jvmErasure
        val isCollection: Boolean = entryKClass.isSubclassOf(Collection::class)
        if(isCollection){
            throw IllegalArgumentException("Nested Collections are not supported")
        } else {
            val type = getType(entryKClass, entryKType)
            return SchemaNode.ReturnType(type, true, isNullable, areEntriesNullable)
        }
    }

    private fun getType(kClass: KClass<*>, kType: KType): SchemaNode.Type {

        fun createMutableType(kType: KType): MutableType {
            val kqlObject = objects.find { it.kClass == kClass } ?: KQLType.Object(kType.typeName(), kClass)
            val type = MutableType(kqlObject)
            typeCache.put(kType, type)
            return type
        }

        fun <T : Any>handleObjectType(type : MutableType, kClass: KClass<T>) : MutableType {
            val kqlObject = type.kqlObjectType

            kClass.memberProperties.filterNot { kqlObject.isIgnored(it) }
                    .associateTo(type.mutableProperties) { property ->
                        property.name to handleKotlinProperty (
                                property, kqlObject.transformations.find { it.kProperty == property }
                        )
                    }

            kqlObject.extensionProperties.associateTo(type.mutableProperties) { property ->
                property.name to handleFunctionProperty(property)
            }

            kqlObject.unionProperties.associateTo(type.mutableUnionProperties) { property ->
                property.name to handleUnionProperty(property)
            }

            return type
        }

        val type = typeCache[kType]
                ?: handleScalarType(kClass)
                ?: handleEnumType(kClass)
                ?: handleObjectType(createMutableType(kType), kClass)
        return type
    }

    private fun <T : Any>handleScalarType(kClass: KClass<T>) : SchemaNode.Type? {
        val kqlScalar = scalars.find { it.kClass == kClass }
        return if(kqlScalar != null) SchemaNode.Type(kqlScalar) else null
    }

    private fun <T : Any>handleEnumType(kClass: KClass<T>) : SchemaNode.Type? {
        val kqlEnum = enums.find { it.kClass == kClass }
        return if(kqlEnum != null) SchemaNode.Type(kqlEnum) else null
    }

    private fun handleFunctionProperty(property: KQLProperty.Function<*>): SchemaNode.Property {
        if(property is KQLProperty.Union){
            throw IllegalArgumentException("Cannot handle union function property")
        } else {
            return SchemaNode.Property(property, handleReturnType(property.kFunction.returnType))
        }
    }

    private fun handleUnionProperty(property: KQLProperty.Union) : SchemaNode.UnionProperty {
        return SchemaNode.UnionProperty(property)
    }

    private fun <T> handleKotlinProperty(property: KProperty1<T, *>, transformation: Transformation<out Any, out Any?>?) : SchemaNode.Property {
        return SchemaNode.Property(KQLProperty.Kotlin(property), handleReturnType(property.returnType), transformation)
    }
}