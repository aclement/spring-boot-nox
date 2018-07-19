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

package io.spring.nox.optimizer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.ElementType;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.springframework.asm.AnnotationVisitor;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.ClassWriter;
import org.springframework.asm.FieldVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.core.io.support.SpringFactoriesLoader;

import io.spring.nox.optimizer.spi.Collector;
import io.spring.nox.optimizer.spi.Rewriter;
import io.spring.nox.type.TypeSystem;

/**
 * @author Andy Clement
 */
public class JarOptimizer {

	private File inputJar;
	private TypeSystem typeSystem;
	private List<Collector> collectors = new ArrayList<>();

	public JarOptimizer(File inputJar) {
		this.inputJar = inputJar;
	}

	public void buildOptimizedVariant() {
		System.out.println("Processing " + inputJar);
		long stime = System.currentTimeMillis();
		createTypeSystem();
		populateCollectors();
		scanJar();
		summarizeCollectedInfo();
		rebuild();
		System.out.println("Completed in " + (System.currentTimeMillis() - stime) + "ms");
	}
	
	private void createTypeSystem() {
		typeSystem = new TypeSystem(inputJar);
	}

	private void populateCollectors() {
		List<Collector> discoveredCollectors = SpringFactoriesLoader.loadFactories(Collector.class, null);
		for (Collector discoveredCollector: discoveredCollectors) {
			discoveredCollector.setTypeSystem(typeSystem);
			collectors.add(discoveredCollector);
		}
	}

	private void summarizeCollectedInfo() {
		System.out.println("\nScan summary:");
		for (Collector collector : collectors) {
			collector.summarize();
		}
	}

	public void scanJar() {
		System.out.println("Scanning boot jar...");
		try {
			try (ZipFile zf = new ZipFile(inputJar)) {
				Enumeration<? extends ZipEntry> entries = zf.entries();
				while (entries.hasMoreElements()) {
					ZipEntry entry = entries.nextElement();
					String entryName = entry.getName();
					if (isAppClass(entryName)) {
						processClass(zf.getInputStream(entry), null);
					} else if (isDependency(entryName)) {
						scanNestedDependencyJar(zf.getInputStream(entry), entry);
					}
				}
			}
		} catch (IOException ioe) {
			throw new RuntimeException("Problem during scan", ioe);
		}
	}

	private boolean isAppClass(String name) {
		return name.startsWith(APP_CLASSES_PREFIX) && name.endsWith(".class");
	}

	private boolean isDependency(String name) {
		return name.startsWith(DEPENDENCY_JARS_PREFIX) && name.endsWith(".jar");
	}
	
	private void rebuild() {
		File outputJar = getOutputJarName();
		System.out.println("\nGenerating "+outputJar);
		byte[] buffer = new byte[100000];
		try {
			try (ZipFile zipIn = new ZipFile(inputJar)) {
				try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outputJar))) {
					Enumeration<? extends ZipEntry> entries = zipIn.entries();
					while (entries.hasMoreElements()) {
						ZipEntry inEntry = entries.nextElement();
						boolean processed = false;
						if (isAppClass(inEntry.getName())) {
							String typename = getTypeName(inEntry.getName());
							List<Rewriter> collectorsThatWantToRewriteApplicationClasses = getAppClassRewriters(typename);
							if (collectorsThatWantToRewriteApplicationClasses.size()!=0) {
								ZipEntry outEntry = new ZipEntry(inEntry);
								outEntry.setCompressedSize(-1);
								zos.putNextEntry(outEntry);
								byte[] bytes = collectorsThatWantToRewriteApplicationClasses.get(0).rewriteClass(zipIn.getInputStream(inEntry), typename,null);
								int i = 1;
								while (i<collectorsThatWantToRewriteApplicationClasses.size()) {
									ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
									bytes =  collectorsThatWantToRewriteApplicationClasses.get(0).rewriteClass(bais, typename,null);
									i++;
								}
								zos.write(bytes);
								processed = true;
							}
						} else if (isDependency(inEntry.getName())) {
							List<Rewriter> collectorsThatWantToRewriteDependency = getDependencyRewriters(inEntry.getName());
							if (!collectorsThatWantToRewriteDependency.isEmpty()) {
								rewriteDependency(zipIn, zos, inEntry, collectorsThatWantToRewriteDependency);
								processed=true;						
							}
						}
						if (!processed) {
							// Copy it across
							ZipEntry outEntry = new ZipEntry(inEntry);
							zos.putNextEntry(outEntry);
							InputStream in = zipIn.getInputStream(inEntry);
							while (in.available() > 0) {
								int read = in.read(buffer);
								zos.write(buffer, 0, read);
							}
							in.close();
						}
					}
					
					// Build cached info class

					ZipEntry outEntry = new ZipEntry("BOOT-INF/classes/org/springframework/core/PrecomputedInfoLoader.class");
					zos.putNextEntry(outEntry);
					byte[] loaderBytes = createPrecomputedInfoLoader();
					zos.write(loaderBytes);
				}
				System.out.println("Rewrite complete: " + outputJar);
			}
		} catch (IOException ioe) {
			throw new RuntimeException("Unexpected problem compiling jar", ioe);
		}
	}

	private List<Rewriter> getAppClassRewriters(String typeName) {
		List<Rewriter> rewriters =  collectors.stream().filter(c -> c instanceof Rewriter).map(c->(Rewriter)c).filter(r ->r.shouldRewriteApplicationClass(typeName)).collect(Collectors.toList());
//		System.out.println("Finding app class rewriters for "+typeName+" = "+rewriters);
		return rewriters;
	}

	private List<Rewriter> getDependencyRewriters(String jarname) {
		List<Rewriter> rewriters = collectors.stream().filter(c -> c instanceof Rewriter).map(c->(Rewriter)c).filter(r ->r.shouldRewriteDependencyJar(jarname)).collect(Collectors.toList());
//		System.out.println("Finding dependency rewriters for jar "+jarname+" = "+rewriters);		
		return rewriters;
	}

	@SuppressWarnings({ "unchecked" })
	private byte[] createPrecomputedInfoLoader() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "org/springframework/core/PrecomputedInfoLoader", null,
				"java/lang/Object", new String[] { "org/springframework/core/PrecomputedInfo$Loader" });

		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD,0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, 
		        "java/lang/Object",
		        "<init>",
		        "()V",
		        false);            
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1,1);
		mv.visitEnd();
		
		mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "populate", "(Ljava/util/Map;)V", null, null);
		mv.visitCode();
		for (Collector collector: collectors) {
			String precomputedKey = collector.getPrecomputedKey();
			Object precomputedInfo = collector.getPrecomputedInfo();
			if (precomputedKey != null) {
				mv.visitVarInsn(Opcodes.ALOAD, 1); // load the map to populate
				pushObject(mv, precomputedKey);
				if (precomputedInfo instanceof Map) {
					mv.visitTypeInsn(Opcodes.NEW, "java/util/HashMap");
					mv.visitInsn(Opcodes.DUP);
					mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V",false);
					Map<Object,Object> m = (Map<Object,Object>)precomputedInfo;
					for (Map.Entry<Object,Object> entry: m.entrySet()) {
						Object k = entry.getKey();
						Object v = entry.getValue();
						mv.visitInsn(Opcodes.DUP);
						pushObject(mv, k);
						pushObject(mv, v);
						mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",true);
						mv.visitInsn(Opcodes.POP);
					}
				} else if (precomputedInfo instanceof List) {
					mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
					mv.visitInsn(Opcodes.DUP);
					mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V",false);
					List<Object> l = (List<Object>)precomputedInfo;
					for (Object o: l) {
						mv.visitInsn(Opcodes.DUP);
						pushObject(mv, o);
						mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z",true);
						mv.visitInsn(Opcodes.POP);
					}
				} else if (precomputedInfo instanceof Boolean) {
					pushObject(mv,precomputedInfo);
				} else {
					throw new IllegalStateException("nyi: no support for "+precomputedInfo.getClass().getName()+" yet");
				}
				mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",true);
			}
		}
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0,0);
		mv.visitEnd();
		
		cw.visitEnd();
		return cw.toByteArray();
	}

	private void pushObject(MethodVisitor mv, Object k) {
		if (k instanceof String) {
			mv.visitLdcInsn((String)k);
		} else if (k instanceof Boolean) {
			mv.visitLdcInsn((Boolean)k);
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;",false);
		} else {
			throw new IllegalStateException("nyi: unable to handle object type "+k.getClass().getName());
		}
	}
	
	private void rewriteDependency(ZipFile zipIn, ZipOutputStream zos, ZipEntry dependencyEntry, List<Rewriter> rewriters) {
		System.out.println("Rewriting dependency "+dependencyEntry.getName());
		byte[] buffer = new byte[100000];
		try {
			InputStream inputStream = zipIn.getInputStream(dependencyEntry);
			ZipInputStream zis = new ZipInputStream(inputStream);
			ZipEntry outDependencyEntry = new ZipEntry(dependencyEntry);
			zos.setMethod(ZipOutputStream.STORED); // TODO need this? (and associated bit below?)

			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ZipOutputStream newDependencyOutputStream = new ZipOutputStream(baos);
			ZipEntry inEntry = zis.getNextEntry();
			while (inEntry != null) {
				String entryName = inEntry.getName();
				byte[] bytes = null;
				boolean wasRewritten = false;
				if (entryName.endsWith(".class")) {
					for (Rewriter rewriter: rewriters) {
//						System.out.println("Asking "+rewriter+" to rewrite "+entryName+" from "+dependencyEntry.getName());
						String typename = entryName.substring(0, entryName.length()-".class".length());
						byte[] newbytes = rewriter.rewriteClass((bytes==null)?zis:new ByteArrayInputStream(bytes), typename, dependencyEntry.getName());
						if (newbytes != null) {
							wasRewritten = true;
							bytes = newbytes;
						}
					}
				}
				if (wasRewritten) {
//					System.out.println("Rewrote "+inEntry.getName()+" inside "+dependencyEntry.getName());
					ZipEntry outEntry = new ZipEntry(inEntry);
					outEntry.setCompressedSize(-1);
					newDependencyOutputStream.putNextEntry(outEntry);
					newDependencyOutputStream.write(bytes);
				} else {
					ZipEntry outEntry = new ZipEntry(inEntry);
					newDependencyOutputStream.putNextEntry(outEntry);
					while (zis.available() > 0) {
						int read = zis.read(buffer);
						if (read >= 0) {
							newDependencyOutputStream.write(buffer, 0, read);
						}
					}
				}
				inEntry = zis.getNextEntry();
			}
			newDependencyOutputStream.flush();
			newDependencyOutputStream.close();
			byte[] bs = baos.toByteArray();
//			outDependencyEntry.setCompressedSize(-1);
			CRC32 checksum = new CRC32();
			checksum.update(bs);
			outDependencyEntry.setCompressedSize(bs.length);
			outDependencyEntry.setSize(bs.length);
			outDependencyEntry.setCrc(checksum.getValue());
			zos.putNextEntry(outDependencyEntry);
			zos.write(bs);
			zos.closeEntry();
			zos.setMethod(ZipOutputStream.DEFLATED);

//			System.out.println("Size of rebuilt inner jar is "+bs.length+"  "+totalSize+"   (#"+count+" entries)");
		} catch (IOException ioe) {
			throw new RuntimeException("Unexpected problem rewriting nested jar: " + dependencyEntry.getName(), ioe);
		}
	}

	private String getTypeName(String name) {
		return name.substring(APP_CLASSES_PREFIX.length(), name.length() - 6);
	}

	private File getOutputJarName() {
		String inputJarName = inputJar.getAbsolutePath();
		String outputJarName = inputJarName.substring(0, inputJarName.length() - 4) + ".nox.jar";
		return new File(outputJarName);
	}

	public final static String APP_CLASSES_PREFIX = "BOOT-INF/classes/";
	public final static String DEPENDENCY_JARS_PREFIX = "BOOT-INF/lib/";

	class ClassInfoCollectorVisitor extends ClassVisitor {

		private ClassInfo ci;

		public ClassInfoCollectorVisitor(int api, ZipEntry containingEntry) {
			super(api);
			ci = new ClassInfo();
			ci.setContainingEntry(containingEntry);
		}

		@Override
		public void visit(int version, int access, String name, String signature, String superName,
				String[] interfaces) {
			super.visit(version, access, name, signature, superName, interfaces);
			ci.setClassName(name);
			ci.setAccess(access);
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
			MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
			return new MethodInfoCollectorVisitor(name + desc, mv);
		}

		@Override
		public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
			FieldVisitor fv = super.visitField(access, name, desc, signature, value);
			return new FieldInfoCollectorVisitor(name + desc, fv);
		}

		/**
		 * Determine the set of optimizers that match this annotation.
		 */
		private List<Collector> checkAnnotations(ElementType type, String desc) {
			List<Collector> result = new ArrayList<>();
			for (Collector collector : collectors) {
				collector.processAnnotation(ci, type, desc);
			}
			return result;
		}

		@Override
		public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
			if (visible) {
				checkAnnotations(ElementType.TYPE, desc);
			}
			return super.visitAnnotation(desc, visible);
		}

		public ClassInfo getClassInfo() {
			return ci;
		}

		class FieldInfoCollectorVisitor extends FieldVisitor implements Opcodes {

			@SuppressWarnings("unused")
			private String namePlusDesc;

			public FieldInfoCollectorVisitor(String string, FieldVisitor fv) {
				super(ASM6, fv);
				this.namePlusDesc = string;
			}

			@Override
			public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
				if (visible) {
					checkAnnotations(ElementType.FIELD, desc);
				}
				return super.visitAnnotation(desc, visible);
			}
		}

		class MethodInfoCollectorVisitor extends MethodVisitor implements org.objectweb.asm.Opcodes {

			String namePlusDesc;

			public MethodInfoCollectorVisitor(String namePlusDesc, MethodVisitor mv) {
				super(ASM6, mv);
				this.namePlusDesc = namePlusDesc;
			}

			@Override
			public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
				if (visible) {
					checkAnnotations(ElementType.METHOD, desc);
				}
				return super.visitAnnotation(desc, visible);
			}

		}

	}

	private ClassInfo processClass(InputStream inputStream, ZipEntry containingEntry) {
		try {
			ClassInfoCollectorVisitor cv = new ClassInfoCollectorVisitor(Opcodes.ASM6, containingEntry);
			ClassReader fileReader = new ClassReader(inputStream);
			fileReader.accept(cv, 0);
			ClassInfo ci = cv.getClassInfo();
			return ci;
		} catch (IOException ioe) {
			throw new IllegalStateException("Unexpected problem loading class from inputstream", ioe);
		}
	}

	private List<ClassInfo> scanNestedDependencyJar(InputStream inputStream, ZipEntry containingEntry) {
		System.out.println("Scanning "+containingEntry.getName());
		List<ClassInfo> classesFromJar = new ArrayList<>();
		try {
			ZipInputStream zis = new ZipInputStream(inputStream);
			ZipEntry ze = zis.getNextEntry();
			while (ze != null) {
				String entryName = ze.getName();
				if (entryName.endsWith(".class")) {
					classesFromJar.add(processClass(zis, containingEntry));
				}
				ze = zis.getNextEntry();
			}
		} catch (IOException ioe) {
			throw new IllegalStateException("Unexpected problem processing jar from inputstream", ioe);
		}
		if (classesFromJar.size() != 0) {
			long configClassesCount = classesFromJar.stream().filter(j -> j.isConfigurationClass()).count();
			if (configClassesCount != 0) {
				System.out.println(containingEntry.getName() + " contains #" + classesFromJar.size() + " classes ("
						+ configClassesCount + " configuration classes)");
			}
		}
		return classesFromJar;
	}

}
