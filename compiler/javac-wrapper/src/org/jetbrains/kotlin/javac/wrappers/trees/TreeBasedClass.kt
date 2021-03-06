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

package org.jetbrains.kotlin.javac.wrappers.trees

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.SearchScope
import com.sun.source.tree.Tree
import com.sun.source.util.TreePath
import com.sun.tools.javac.code.Flags
import com.sun.tools.javac.tree.JCTree
import com.sun.tools.javac.tree.TreeInfo
import org.jetbrains.kotlin.descriptors.Visibilities.PUBLIC
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.javac.JavacWrapper
import org.jetbrains.kotlin.load.java.structure.*
import org.jetbrains.kotlin.load.java.structure.impl.VirtualFileBoundJavaClass
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import javax.tools.JavaFileObject

class TreeBasedClass(
        tree: JCTree.JCClassDecl,
        treePath: TreePath,
        javac: JavacWrapper,
        val file: JavaFileObject
) : TreeBasedElement<JCTree.JCClassDecl>(tree, treePath, javac), VirtualFileBoundJavaClass {

    override val name: Name
        get() = Name.identifier(tree.simpleName.toString())

    override val annotations: Collection<JavaAnnotation> by lazy {
        tree.annotations().map { annotation -> TreeBasedAnnotation(annotation, treePath, javac) }
    }

    override fun findAnnotation(fqName: FqName) =
            annotations.find { it.classId?.asSingleFqName() == fqName }

    override val isDeprecatedInJavaDoc: Boolean
        get() = false

    override val isAbstract: Boolean
        get() = tree.modifiers.isAbstract || (isAnnotationType && methods.any { it.isAbstract })

    override val isStatic: Boolean
        get() = (outerClass?.isInterface ?: false) || tree.modifiers.isStatic

    override val isFinal: Boolean
        get() = tree.modifiers.isFinal

    override val visibility: Visibility
        get() = if (outerClass?.isInterface ?: false) PUBLIC else tree.modifiers.visibility

    override val typeParameters: List<JavaTypeParameter>
        get() = tree.typeParameters.map { parameter ->
            TreeBasedTypeParameter(parameter, TreePath(treePath, parameter), javac)
        }

    override val fqName: FqName =
            treePath.reversed()
                    .filterIsInstance<JCTree.JCClassDecl>()
                    .joinToString(
                            separator = ".",
                            prefix = "${treePath.compilationUnit.packageName}.",
                            transform = JCTree.JCClassDecl::name
                    )
                    .let(::FqName)

    override val supertypes: Collection<JavaClassifierType>
        get() = arrayListOf<JavaClassifierType>().apply {
            fun JCTree.mapToJavaClassifierType() = when {
                this is JCTree.JCTypeApply -> TreeBasedGenericClassifierType(this, TreePath(treePath, this), javac)
                this is JCTree.JCExpression -> TreeBasedNonGenericClassifierType(this, TreePath(treePath, this), javac)
                else -> null
            }

            if (isEnum) {
                javac.JAVA_LANG_ENUM?.let(this::add)
            } else if (isAnnotationType) {
                javac.JAVA_LANG_ANNOTATION_ANNOTATION?.let(this::add)
            }

            tree.implementing?.mapNotNull { it.mapToJavaClassifierType() }?.let(this::addAll)
            tree.extending?.let { it.mapToJavaClassifierType()?.let(this::add) }

            if (isEmpty()) {
                javac.JAVA_LANG_OBJECT?.let(this::add)
            }
        }

    val innerClasses: Map<Name, TreeBasedClass> by lazy {
        tree.members
                .filterIsInstance(JCTree.JCClassDecl::class.java)
                .map { TreeBasedClass(it, TreePath(treePath, it), javac, file) }
                .associateBy(JavaClass::name)
    }

    override val outerClass: JavaClass? by lazy {
        (treePath.parentPath.leaf as? JCTree.JCClassDecl)?.let { classDecl ->
            TreeBasedClass(classDecl, treePath.parentPath, javac, file)
        }
    }

    override val isInterface: Boolean
        get() = tree.modifiers.flags and Flags.INTERFACE.toLong() != 0L

    override val isAnnotationType: Boolean
        get() = tree.modifiers.flags and Flags.ANNOTATION.toLong() != 0L

    override val isEnum: Boolean
        get() = tree.modifiers.flags and Flags.ENUM.toLong() != 0L

    override val lightClassOriginKind: LightClassOriginKind?
        get() = null

    override val methods: Collection<JavaMethod>
        get() = tree.members
                .filter { it.kind == Tree.Kind.METHOD && !TreeInfo.isConstructor(it) }
                .map { TreeBasedMethod(it as JCTree.JCMethodDecl, TreePath(treePath, it), this, javac) }

    override val fields: Collection<JavaField>
        get() = tree.members
                .filterIsInstance(JCTree.JCVariableDecl::class.java)
                .map { TreeBasedField(it, TreePath(treePath, it), this, javac) }

    override val constructors: Collection<JavaConstructor>
        get() = tree.members
                .filter { member -> TreeInfo.isConstructor(member) }
                .map { constructor ->
                    TreeBasedConstructor(constructor as JCTree.JCMethodDecl, TreePath(treePath, constructor), this, javac)
                }

    override val innerClassNames: Collection<Name>
        get() = innerClasses.keys

    override val virtualFile: VirtualFile? by lazy {
        javac.toVirtualFile(file)
    }

    override fun isFromSourceCodeInScope(scope: SearchScope): Boolean = true

    override fun findInnerClass(name: Name) = innerClasses[name]

}
