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
package io.spring.nox.type;

import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/**
 * Quick n dirty type system based on boot jar contents (plus backing JDK).
 * 
 * @author Andy Clement
 */
public class TypeSystem {

	private File jarpath;

	private BootJarScanner scanner;

	private Map<String, Type> typeCache = new HashMap<>();

	public TypeSystem(File jarpath) {
		this.jarpath = jarpath;
		this.scanner = new BootJarScanner(jarpath);
	}

	public static TypeSystem forBootJar(String jarpath) {
		return forBootJar(new File(jarpath));
	}
	
	public static TypeSystem forBootJar(File jarpath) {
		TypeSystem typeSystem = new TypeSystem(jarpath);
		return typeSystem;
	}

	public Type resolveDotted(String dottedTypeName) {
		String slashedTypeName = toSlashedName(dottedTypeName);
		return resolveSlashed(slashedTypeName);
	}
	
	public boolean canResolveSlashed(String slashedTypeName) {
		try { 
			return resolveSlashed(slashedTypeName) != null;
		} catch (RuntimeException re) {
			if (re.getMessage().startsWith("Unable to find class file for")) {
				return false;
			}
			throw re;
		}
	}
	
	public static class MissingTypeException extends RuntimeException {
		private static final long serialVersionUID = 1L;
		private String typename;
		
		public MissingTypeException(String slashedTypeName) {
			this.typename = slashedTypeName;
		}

		@Override
		public String getMessage() {
			return "Unable to find class file for "+typename;
		}
	}
	
	public Type resolveSlashed(String slashedTypeName) {
		Type type = typeCache.get(slashedTypeName);
		if (type==Type.MISSING) {
			throw new MissingTypeException(slashedTypeName);			
		}
		if (type!= null) {
			return type;
		}
		byte[] bytes = scanner.find(slashedTypeName);
		if (bytes == null) {
			// System class?
			InputStream resourceAsStream = Thread.currentThread().getContextClassLoader()
					.getResourceAsStream(slashedTypeName + ".class");
			if (resourceAsStream == null) {
				// cache a missingtype so we don't go looking again!
				typeCache.put(slashedTypeName, Type.MISSING);
				throw new MissingTypeException(slashedTypeName);
			}
			try {
				bytes = BootJarScanner.loadFromStream(resourceAsStream);
			} catch (RuntimeException e) {
				throw new RuntimeException("Problems loading class from resource stream: "+slashedTypeName, e);
			}
		}
		ClassNode node = new ClassNode();
		ClassReader reader = new ClassReader(bytes);
		reader.accept(node, ClassReader.SKIP_DEBUG);
		type = Type.forClassNode(this, node);
		typeCache.put(slashedTypeName, type);
		return type;
	}

	private String toSlashedName(String dottedTypeName) {
		return dottedTypeName.replace(".", "/");
	}

	public boolean canResolve(String classname) {
		if (classname.contains(".")) {
			throw new RuntimeException("Dont pass dotted names to resolve() :"+classname);
		}
		return canResolveSlashed(classname);		
	}

	public Type resolve(String classname) {
		if (classname.contains(".")) {
			throw new RuntimeException("Dont pass dotted names to resolve() :"+classname);
		}
		return resolveSlashed(classname);
	}

	public Type Lresolve(String desc) {
		return resolve(desc.substring(1,desc.length()-1));
	}

	public File getJarpath() {
		return jarpath;
	}

}