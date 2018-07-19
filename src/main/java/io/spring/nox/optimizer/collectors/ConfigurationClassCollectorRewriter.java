/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.spring.nox.optimizer.collectors;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.ElementType;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;

import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.ClassWriter;
import org.springframework.asm.Label;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;

import io.spring.nox.optimizer.ClassInfo;
import io.spring.nox.optimizer.Utils;
import io.spring.nox.optimizer.spi.Collector;
import io.spring.nox.optimizer.spi.Rewriter;
import io.spring.nox.type.Method;
import io.spring.nox.type.Type;
import io.spring.nox.type.TypeSystem;

/**
 * @author Andy Clement
 */
public class ConfigurationClassCollectorRewriter implements Collector, Rewriter {
	
	public final static String CONFIGURATION_ANNOTATION = "Lorg/springframework/context/annotation/Configuration;";

	private TypeSystem typeSystem;
	private List<ClassInfo> configurationClasses = new ArrayList<>();
	
	public void setTypeSystem(TypeSystem typeSystem) {
		this.typeSystem = typeSystem;
	}
	
	@Override
	public void processAnnotation(ClassInfo ci, ElementType type, String desc) {
		if (type != ElementType.TYPE || ci.isInterface()) {
			return;
		}
		if (desc.equals(CONFIGURATION_ANNOTATION)
				|| isUsedAsMetaAnnotation(typeSystem, desc, CONFIGURATION_ANNOTATION)) {
			configurationClasses.add(ci);
		}
	}

	@Override
	public void summarize() {
		int applicationConfigurationClasses = 0;
		Map<String, Integer> dependencyConfigurationClasses = new HashMap<>();
		for (ClassInfo configurationClass: configurationClasses) {
			ZipEntry containingEntry = configurationClass.getContainingEntry();
			if (containingEntry == null) {
				applicationConfigurationClasses++;
			} else {
				Integer count = dependencyConfigurationClasses.get(containingEntry.getName());
				if (count == null) {
					dependencyConfigurationClasses.put(containingEntry.getName(),1);
				} else {
					dependencyConfigurationClasses.put(containingEntry.getName(),count+1);
				}
			}
		}
		System.out.println("ConfigurationClassCollector");
		System.out.println("Application configuration class count: "+applicationConfigurationClasses);
		for (Map.Entry<String,Integer> entry: dependencyConfigurationClasses.entrySet() ) {
			System.out.println(entry.getKey()+" configuration class count: "+entry.getValue());
		}
	}

	@Override
	public boolean shouldRewriteApplicationClass(String typeName) {
//		System.out.println("Checking "+configurationClasses+" for "+typeName);
		return configurationClasses.stream().
				filter(ci -> ci.isApplicationClass()).
				filter(ci->ci.getTypeName().equals(typeName)).findAny().isPresent();
	}


	@Override
	public boolean shouldRewriteDependencyJar(String jarname) {
		return configurationClasses.stream().
				filter(ci -> !ci.isApplicationClass()).
				filter(ci -> ci.getContainingEntry().getName().equals(jarname)).
//				filter(ci-> { System.out.println("dependency: "+ci);return true;}).
				findAny().isPresent();
	}

	@Override
	public byte[] rewriteClass(InputStream classInputStream, String typename, String containingEntryName) {
		if (containingEntryName != null) {
			// Need to double check as we have said we are interested in rewriting something in a dependency, but is
			// this it?
			if (!configurationClasses.stream()
					.filter(ci -> ci.getContainingEntry()!=null && ci.getContainingEntry().getName().equals(containingEntryName))
					.filter(ci->ci.getTypeName().equals(typename)).findFirst().isPresent()) {
				return null;
			}
		}
//		System.out.println("Rewriting "+typename+" "+(containingEntryName==null?"":"from "+containingEntryName));
		try {
			ClassReader fileReader = new ClassReader(classInputStream);
			ClassWriter cw = new TypeSystemAwareClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS,
					typeSystem);
			ConfigurationClassAdapter gca = new ConfigurationClassAdapter(cw);
			fileReader.accept(gca, 0);
			return cw.toByteArray();
		} catch (IOException ioe) {
			throw new RuntimeException("Unexpected problem rewriting application class", ioe);
		}
	}

	static class TypeSystemAwareClassWriter extends ClassWriter {

		private TypeSystem typeSystem;

		public TypeSystemAwareClassWriter(int i, TypeSystem typeSystem) {//, ScanResult scanResult) {
			super(i);
			this.typeSystem = typeSystem;
		}

		// Implementation of getCommonSuperClass() that avoids Class.forName() - this needs a bunch of work!!!
		@Override
		protected String getCommonSuperClass(final String type1, final String type2) {

//			ResolvedType resolvedType1 = world.resolve(UnresolvedType.forName(type1.replace('/', '.')));
			Type resolvedType1 = typeSystem.resolveSlashed(type1);
//			ResolvedType resolvedType2 = world.resolve(UnresolvedType.forName(type2.replace('/', '.')));
			Type resolvedType2 = typeSystem.resolveSlashed(type2);

			if (resolvedType1.isAssignableFrom(resolvedType2)) {
				return type1;
			}

			if (resolvedType2.isAssignableFrom(resolvedType1)) {
				return type2;
			}

			if (resolvedType1.isInterface() || resolvedType2.isInterface()) {
				return "java/lang/Object";
			} else {
				do {
					resolvedType1 = resolvedType1.getSuperclass();
					if (resolvedType1 == null) {
						// This happens if some types are missing, the getSuperclass() call on
						// MissingResolvedTypeWithKnownSignature will return the Missing type which
						// in turn returns a superclass of null. By returning Object here it
						// should surface the cantFindType message raised in the first problematic
						// getSuperclass call
						return "java/lang/Object";
					}
//					if (resolvedType1.isParameterizedOrGenericType()) {
//						resolvedType1 = resolvedType1.getRawType();
//					}
				} while (!resolvedType1.isAssignableFrom(resolvedType2));
				return resolvedType1.getName();// resolvedType1.getRawName().replace('.', '/');
			}
		}
	}

	// What we need to do:
	// #1 Add EnhancedConfiguration marker interface
	// #2 Add field 'public org.springframework.beans.factory.BeanFactory
	// $$beanFactory'
	// #3 Add method 'public final void setBeanFactory(BeanFactory)' which sets the
	// local field and delegates
	// to any super setBeanFactory method if appropriate.
	// NOTE: needs to account for the class already having a setBeanFactory() method
	// in it, in which case just augment
	// it to store the bean factory and proceed.
	// #4 Intercept @Bean marked methods and rewrite them to check the factory

	class ConfigurationClassAdapter extends ClassVisitor implements Opcodes {

		private String classname;
		private boolean skipThisClass = false;
		private boolean hasBeanFactoryMethod = false;
		private boolean processedClinit = false;

		private Map<Method, String> beanMethods = null;

		public ConfigurationClassAdapter(ClassWriter cw) {
			super(ASM6, cw);
		}

		@Override
		public void visit(int version, int access, String name, String signature, String superName,
				String[] interfaces) {
			classname = name;
			if (!typeSystem.canResolve(superName)) {
				skipThisClass = true;
//				System.out.println("Skipping rewrite of " + classname
//						+ " since superclass cannot be resolved on classpath (" + superName + ")");
			} else {
				// TODO skip if already NoxEnhancedConfiguration
				determineBeanMethods();
				// #1
				interfaces = augment(interfaces,
						"org/springframework/context/annotation/ConfigurationClassEnhancer$NoxEnhancedConfiguration");
			}
			super.visit(version, access, name, signature, superName, interfaces);
		}

		public void determineBeanMethods() {
			if (skipThisClass) {
				return;
			}
			if (beanMethods == null) {
				Type type = typeSystem.resolve(classname);
				List<Method> methods = type.getMethodsWithAnnotation("Lorg/springframework/context/annotation/Bean;");
				if (methods.size() == 0) {
					beanMethods = Collections.emptyMap();
				} else {
					int id = 0;
					beanMethods = new HashMap<>();
					for (Method m : methods) {
						beanMethods.put(m, "spring$beanmethod$" + (id++));
					}
				}
			}
		}

		public void addClinit() {
//			MethodVisitor mv = cv.visitMethod(ACC_PUBLIC | ACC_STATIC, "<clinit>", "()V", null, null);
//			mv.visitCode();
//			this.nextFreeVariableId = 0;  // to 0 because there is no 'this' in a clinit
//			for (ClinitAdder clinitAdder : this.clinitAdders) {
//				clinitAdder.generateCode(mv, this);
//			}
//			mv.visitInsn(RETURN);
//			mv.visitMaxs(0,0);  // not supplied due to COMPUTE_MAXS
//			mv.visitEnd();
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
			if (skipThisClass) {
				return super.visitMethod(access, name, desc, signature, exceptions);
			}
			if (name.equals("<clinit>")) {
				processedClinit = true;
				if (beanMethods.size() != 0) {
					MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
					return new ClinitAppendingMethodVisitor(mv, classname);
				} else {
					return super.visitMethod(access, name, desc, signature, exceptions);
				}
			}
			if (name.equals("setBeanFactory") && desc.equals("(Lorg/springframework/beans/factory/BeanFactory;)V")) {
				// #3
				hasBeanFactoryMethod = true;
				MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
				return new CacheBeanFactoryMethodVisitor(classname + ":" + name, classname, mv);
			} else {
				boolean isBeanAnnotated = false;
				for (Method beanMethod : beanMethods.keySet()) {
					if (beanMethod.getName().equals(name) && beanMethod.getDesc().equals(desc)) {
						isBeanAnnotated = true;
						break;
					}
				}
				// Example of static: PropertyPlaceholderAutoConfiguration
				if (isBeanAnnotated && !Modifier.isStatic(access)) {
					MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
					return new InterceptingMethodVisitor(classname + ":" + name, classname, name, desc, beanMethods,
							mv);
				} else {
					return super.visitMethod(access, name, desc, signature, exceptions);
				}
			}
		}

		private String[] augment(String[] input, String string) {
			String[] newStrings = new String[input.length + 1];
			System.arraycopy(input, 0, newStrings, 0, input.length);
			newStrings[input.length] = string;
			return newStrings;
		}

		@Override
		public void visitEnd() {
			if (skipThisClass) {
				super.visitEnd();
				return;
			}
			super.visitField(ACC_PRIVATE | ACC_STATIC, "$$interceptor",
					"Lorg/springframework/context/annotation/ConfigurationClassEnhancer$BeanMethodInterceptor;", null,
					null);
			MethodVisitor mv = super.visitMethod(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "fixInterceptor",
					"(Lorg/springframework/context/annotation/ConfigurationClassEnhancer$BeanMethodInterceptor;)V",
					null, null);
			mv.visitVarInsn(ALOAD, 0);
			mv.visitFieldInsn(PUTSTATIC, classname, "$$interceptor",
					"Lorg/springframework/context/annotation/ConfigurationClassEnhancer$BeanMethodInterceptor;");
			mv.visitInsn(RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();

			// #2
			super.visitField(ACC_PUBLIC, "$$beanFactory", "Lorg/springframework/beans/factory/BeanFactory;", null,
					null);

			// #3
			if (!hasBeanFactoryMethod) {
				// Need to insert the method
				// the removal of final because of two configs in a hierarchy
				mv = super.visitMethod(ACC_PUBLIC /* TODO check? | ACC_FINAL */, "setBeanFactory",
						"(Lorg/springframework/beans/factory/BeanFactory;)V", null,
						toArray("org/springframework/beans/BeansException"));
				mv.visitVarInsn(ALOAD, 0);
				mv.visitVarInsn(ALOAD, 1);
				mv.visitFieldInsn(PUTFIELD, classname, "$$beanFactory",
						"Lorg/springframework/beans/factory/BeanFactory;");
				Type clazz = typeSystem.resolve(classname);
				Type superclass = clazz.getSuperclass();
				if (superclass.implementsInterface("org/springframework/beans/factory/BeanFactoryAware")
						|| shouldRewriteClass(superclass.getName())) {
					mv.visitVarInsn(ALOAD, 0);
					mv.visitVarInsn(ALOAD, 1);
					mv.visitMethodInsn(INVOKESPECIAL, superclass.getName(), "setBeanFactory",
							"(Lorg/springframework/beans/factory/BeanFactory;)V", false);
				}
				mv.visitInsn(RETURN);
				mv.visitMaxs(2, 0);
				mv.visitEnd();
			}
			if (beanMethods.size() != 0) {
				for (Map.Entry<Method, String> entry : beanMethods.entrySet()) {
					super.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, entry.getValue(),
							"Ljava/lang/reflect/Method;", null, null);
				}
			}
			if (beanMethods.size() != 0) {
				String name = "$$configClinit";
				if (!processedClinit) {
					name = "<clinit>";
				}
				mv = cv.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "()V", null, null);
				mv.visitCode();
				Label start = new Label();
				Label end = new Label();
				Label theReturn = new Label();
				Label handler = new Label();
				mv.visitTryCatchBlock(start, end, handler, "java/lang/Exception");
				mv.visitLabel(start);
				for (Map.Entry<Method, String> entry : beanMethods.entrySet()) {
					mv.visitLdcInsn(org.springframework.asm.Type.getType("L" + classname + ";"));
					mv.visitLdcInsn(entry.getKey().getName());
					// TODO do the correct thing for the descriptor, rather than assuming no
					// params...
					if (!entry.getKey().getDesc().startsWith("()")) {
//						System.out.println("Tricky one "+entry.getKey().getDesc());
						Utils.pushParamArray(mv, entry.getKey().getDesc());
//									throw new IllegalStateException("ARGH! " + classname + "." + entry.getKey());
					} else {
						mv.visitLdcInsn(0);
						mv.visitTypeInsn(ANEWARRAY, "java/lang/Class");
					}
					mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getDeclaredMethod",
							"(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
					mv.visitFieldInsn(PUTSTATIC, classname, entry.getValue(), "Ljava/lang/reflect/Method;");
				}
				mv.visitLabel(end);
				mv.visitJumpInsn(GOTO, theReturn);
				mv.visitLabel(handler);
				mv.visitVarInsn(ASTORE, 0);
				mv.visitLabel(theReturn);
				mv.visitInsn(RETURN);
				mv.visitMaxs(0, 0);
				mv.visitEnd();
			}
			super.visitEnd();
		}

		public boolean shouldRewriteClass(String name) {
			// TODO shouldn't this check application classes too?
			return configurationClasses.stream().filter(ci->!ci.isApplicationClass()).filter(ci -> ci.getTypeName().equals(name)).findFirst().isPresent();			
		}
		
		private String[] toArray(String... strings) {
			return strings;
		}
	}

	/**
	 * Insert code at the start of a setBeanFactory method to cache the bean factory
	 * in our custom field.
	 */
	static class CacheBeanFactoryMethodVisitor extends MethodVisitor implements Opcodes {

		String id;
		String classname;

		public CacheBeanFactoryMethodVisitor(String id, String classname, MethodVisitor mv) {
			super(ASM6, mv);
			this.classname = classname;
			this.id = id;
		}

		@Override
		public void visitCode() {
			super.visitCode();
			mv.visitVarInsn(ALOAD, 0);
			mv.visitVarInsn(ALOAD, 1);
			mv.visitFieldInsn(PUTFIELD, classname, "$$beanFactory", "Lorg/springframework/beans/factory/BeanFactory;");
		}
	}

	static class ClinitAppendingMethodVisitor extends MethodVisitor implements Opcodes {

		String classname;

		public ClinitAppendingMethodVisitor(MethodVisitor mv, String classname) {
			super(ASM6, mv);
			this.classname = classname;
		}

		@Override
		public void visitCode() {
			super.visitCode();
			super.visitMethodInsn(INVOKESTATIC, classname, "$$configClinit", "()V", false);
		}
	}

	public class InterceptingMethodVisitor extends MethodVisitor implements Opcodes {

		String id, classname, methodname, methoddesc;
		private Map<Method, String> beanMethods;

		public InterceptingMethodVisitor(String id, String classname, String methodname, String methoddesc,
				Map<Method, String> beanMethods, MethodVisitor mv) {
			super(ASM6, mv);
			this.id = id;
			this.classname = classname;
			this.methodname = methodname;
			this.methoddesc = methoddesc;
			this.beanMethods = beanMethods;
		}

		/*
		 * @Bean Foo getFoo(String s) { return new Foo(s); }
		 * 
		 * becomes:
		 * 
		 * @Bean Foo getFoo(String s) { String beanName =
		 * interceptor.getBeanName(beanFactory, beanMethod); Foo bean =
		 * interceptor.checkFactory(beanFactory, beanMethod, beanName) if (bean != null)
		 * { return bean; } if (interceptor.isCurrentlyInvokedFactoryMethod(beanMethod))
		 * { return new Foo(s); } return interceptor.resolveBeanReference(beanName,
		 * beanMethod, beanMethodArgs, beanFactory); }
		 */
		Label avoidOriginalCode;

		private void loadInterceptorField() {
			mv.visitFieldInsn(GETSTATIC, classname, "$$interceptor",
					"Lorg/springframework/context/annotation/ConfigurationClassEnhancer$BeanMethodInterceptor;");
		}

		private void loadBeanMethodField(String beanMethodFieldName) {
			mv.visitFieldInsn(GETSTATIC, classname, beanMethodFieldName, "Ljava/lang/reflect/Method;");
		}

		private void loadBeanFactoryField() {
			mv.visitVarInsn(ALOAD, 0);
			mv.visitFieldInsn(GETFIELD, classname, "$$beanFactory", "Lorg/springframework/beans/factory/BeanFactory;");
			// TODO remove need for this - adjust the type of the $$beanFactory field and
			// setter
			mv.visitTypeInsn(CHECKCAST, "org/springframework/beans/factory/config/ConfigurableBeanFactory");
		}

		@Override
		public void visitCode() {
			super.visitCode();
			String beanMethodFieldName = getBeanmethodField(methodname, methoddesc);

			// beanName = interceptor.getBeanName(beanFactory, beanMethod);
			loadInterceptorField();
			loadBeanFactoryField();
			loadBeanMethodField(beanMethodFieldName);
			mv.visitMethodInsn(INVOKEVIRTUAL,
					"org/springframework/context/annotation/ConfigurationClassEnhancer$BeanMethodInterceptor",
					"getBeanName",
					"(Lorg/springframework/beans/factory/config/ConfigurableBeanFactory;Ljava/lang/reflect/Method;)Ljava/lang/String;",
					false);
			mv.visitInsn(DUP);
			// STACK = beanName:beanName

			// Foo bean = interceptor.checkFactory(beanFactory, beanMethod, beanName)
			loadInterceptorField();
			mv.visitInsn(SWAP); // STACK = beanName:interceptor:beanName
			loadBeanFactoryField();
			loadBeanMethodField(beanMethodFieldName);
			mv.visitMethodInsn(INVOKEVIRTUAL,
					"org/springframework/context/annotation/ConfigurationClassEnhancer$BeanMethodInterceptor",
					"checkFactory",
					"(Ljava/lang/String;Lorg/springframework/beans/factory/config/ConfigurableBeanFactory;Ljava/lang/reflect/Method;)Ljava/lang/Object;",
					false);

			// STACK = beanName:Object
			mv.visitInsn(DUP);
			Label notFound = new Label();
			mv.visitJumpInsn(IFNULL, notFound);
			mv.visitInsn(SWAP);
			mv.visitInsn(POP); // clearing up after ourselves (removing bean name)
			mv.visitTypeInsn(CHECKCAST, getReturnDescriptor(methoddesc));
			mv.visitInsn(ARETURN);
			mv.visitLabel(notFound);
			mv.visitInsn(POP); // popping the null

			// STACK = beanName

			// if (interceptor.isCurrentlyInvokedFactoryMethod(beanMethod)) {
			loadInterceptorField();
			loadBeanMethodField(beanMethodFieldName);
			mv.visitMethodInsn(INVOKEVIRTUAL,
					"org/springframework/context/annotation/ConfigurationClassEnhancer$BeanMethodInterceptor",
					"isCurrentlyInvokedFactoryMethodCheck", "(Ljava/lang/reflect/Method;)Z", false);
			avoidOriginalCode = new Label();
			mv.visitJumpInsn(IFEQ, avoidOriginalCode);
			mv.visitInsn(POP); // lose beanName
		}

		private String getReturnDescriptor(String methoddesc) {
			// Strip parameters, leading L and final ;
			// Assumes not primitive (or array!) return type
			return methoddesc.substring(methoddesc.indexOf(")L") + 2, methoddesc.length() - 1);
		}

		@Override
		public void visitMaxs(int maxStack, int maxLocals) {
			mv.visitLabel(avoidOriginalCode);
			// return interceptor.resolveBeanRef(beanName, beanMethod, beanMethodArgs,
			// beanFactory);
			loadInterceptorField();
			mv.visitInsn(SWAP); // STACK = interceptorField:beanName
			loadBeanMethodField(getBeanmethodField(methodname, methoddesc));
			if (!methoddesc.startsWith("()")) {
				Utils.pushParamArray(mv, methoddesc);
			} else {
				mv.visitLdcInsn(0);
				mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
			}
			loadBeanFactoryField();
			mv.visitMethodInsn(INVOKEVIRTUAL,
					"org/springframework/context/annotation/ConfigurationClassEnhancer$BeanMethodInterceptor",
					"resolveBeanRef",
					"(Ljava/lang/String;Ljava/lang/reflect/Method;[Ljava/lang/Object;Lorg/springframework/beans/factory/config/ConfigurableBeanFactory;)Ljava/lang/Object;",
					false);
			mv.visitTypeInsn(CHECKCAST, getReturnDescriptor(methoddesc));
			mv.visitInsn(ARETURN);
			super.visitMaxs(0, 0);
		}

		@Override
		public void visitEnd() {
			super.visitEnd();
		}

		private String getBeanmethodField(String methodname2, String methoddesc2) {
			for (Map.Entry<Method, String> beanMethod : beanMethods.entrySet()) {
				if (beanMethod.getKey().getName().equals(methodname2)
						&& beanMethod.getKey().getDesc().equals(methoddesc2)) {
					return beanMethod.getValue();
				}
			}
			throw new IllegalStateException("huh?");
		}

	}
	
	@Override
	public String toString() {
		return "ConfigurationClassCollectorRewriter";
	}

	@Override
	public String getPrecomputedKey() {
		return null;
	}

	@Override
	public Object getPrecomputedInfo() {
		return null;
	}
}