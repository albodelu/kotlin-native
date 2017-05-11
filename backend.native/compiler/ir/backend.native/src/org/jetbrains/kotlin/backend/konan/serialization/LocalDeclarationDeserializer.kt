/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.backend.konan.serialization

import org.jetbrains.kotlin.backend.konan.descriptors.allContainingDeclarations
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.impl.LocalVariableDescriptor
import org.jetbrains.kotlin.serialization.Flags
import org.jetbrains.kotlin.serialization.KonanIr
import org.jetbrains.kotlin.serialization.KonanLinkData
import org.jetbrains.kotlin.serialization.ProtoBuf
import org.jetbrains.kotlin.serialization.ProtoBuf.QualifiedNameTable.QualifiedName
import org.jetbrains.kotlin.serialization.deserialization.MemberDeserializer
import org.jetbrains.kotlin.serialization.deserialization.NameResolverImpl
import org.jetbrains.kotlin.serialization.deserialization.TypeTable
import org.jetbrains.kotlin.serialization.deserialization.descriptors.*
import org.jetbrains.kotlin.serialization.deserialization.descriptors.SinceKotlinInfoTable
import org.jetbrains.kotlin.serialization.deserialization.findClassAcrossModuleDependencies
import org.jetbrains.kotlin.types.KotlinType

// This class knows how to construct contexts for 
// MemberDeserializer to deserialize descriptors declared in IR.
// Eventually, these descriptors shall be reconstructed from IR declarations,
// or may be just go away completely.

class LocalDeclarationDeserializer(val parentDescriptor: DeclarationDescriptor) {

    val tower: List<DeclarationDescriptor> = (listOf(parentDescriptor) + parentDescriptor.allContainingDeclarations()).reversed()
    init {
        assert(tower[0] is ModuleDescriptor)
    }
    val pkg = tower[1] as KonanPackageFragment
    // skip the module and the package
    val parents = tower.drop(2)

    val components = pkg.components
    val nameTable = pkg.proto.nameTable
    val nameResolver = NameResolverImpl(pkg.proto.stringTable, nameTable)
    val packageTypeTable = TypeTable(pkg.proto.getPackage().typeTable)
    val packageContext = components.createContext(
        pkg, nameResolver, packageTypeTable, SinceKotlinInfoTable.EMPTY, null)
  
    var parentContext = packageContext
    var parentTypeTable = packageTypeTable

    init {
        // Now walk down all the containing declarations to construct
        // the tower of deserialization contexts.
        parents.forEach{
            // Only packages and classes have their type tables.
            if (it is DeserializedClassDescriptor) {
                parentTypeTable = TypeTable(it.classProto.typeTable)
            }
            parentContext = parentContext.childContext(
                    it, it.typeParameterProtos, nameResolver, parentTypeTable)
        }
    }

    val typeParameterProtos = parentDescriptor.typeParameterProtos

    val typeDeserializer = parentContext.typeDeserializer

    val memberDeserializer = MemberDeserializer(parentContext)

    fun deserializeInlineType(type: ProtoBuf.Type): KotlinType {
        val result = typeDeserializer.type(type)

        return result
    }

    fun deserializeClass(irProto: KonanIr.KotlinDescriptor): ClassDescriptor {
        return DeserializedClassDescriptor(parentContext, irProto.irLocalDeclaration.descriptor.clazz, nameResolver, SourceElement.NO_SOURCE)


    }

    fun deserializeFunction(irProto: KonanIr.KotlinDescriptor): FunctionDescriptor =
        memberDeserializer.loadFunction(irProto.irLocalDeclaration.descriptor.function)

    fun deserializeConstructor(irProto: KonanIr.KotlinDescriptor): ConstructorDescriptor {

       val proto = irProto.irLocalDeclaration.descriptor.constructor
       val isPrimary = !Flags.IS_SECONDARY.get(proto.flags)
       val constructor = memberDeserializer.loadConstructor(proto, isPrimary)

       return constructor
    }

    fun deserializeProperty(irProto: KonanIr.KotlinDescriptor): VariableDescriptor {

        val proto = irProto.irLocalDeclaration.descriptor.property
        val property = memberDeserializer.loadProperty(proto)

        return if (proto.getExtension(KonanLinkData.usedAsVariable)) {
            propertyToVariable(property)
        } else {
            property
        }
    }

    fun propertyToVariable(property: PropertyDescriptor): LocalVariableDescriptor {
        val variable = LocalVariableDescriptor(
            property.containingDeclaration,
            property.annotations,
            property.name,
            property.type,
            property.isVar, 
            property.isDelegated,
            SourceElement.NO_SOURCE)

        // TODO: Should we transform the getter and the setter too?
        return variable
    }
}


