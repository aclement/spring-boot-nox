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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class BootJarScanner {

	public final static String APP_CLASSES_PREFIX = "BOOT-INF/classes/";
	public final static String DEPENDENCIES_PREFIX = "BOOT-INF/lib/";

	private File bootJarPath;

	private Map<String, ZipEntry> packageCache = new HashMap<>();

	private Map<String, ZipEntry> appClasses = new HashMap<>();

	BootJarScanner(File bootJarPath) {
		System.out.println("Initializing type system based on boot jar: "+bootJarPath);
		this.bootJarPath = bootJarPath;
		index();
		System.out.println( "#"+appClasses.size()+" application classes");
		System.out.println("#" + packageCache.values().stream().distinct().count() + " dependencies containing #"
				+ packageCache.keySet().size() + " packages");
		System.out.println();
	}

	public void index() {
		// Walk the jar, index entries and cache package > entry
		try {
			try (ZipFile zf = new ZipFile(bootJarPath)) {
				Enumeration<? extends ZipEntry> entries = zf.entries();
				while (entries.hasMoreElements()) {
					ZipEntry entry = entries.nextElement();
					// Interesting entries:
					// - infra class files (the application plus 'infrastructure' for getting going)
					// under org/springframework/boot
					// - app class files under BOOT-INF/classes
					// - dependencies under BOOT-INF/lib
					String entryName = entry.getName();
					if (isAppClass(entryName)) {
						String typeName = entryName.substring(APP_CLASSES_PREFIX.length(), entryName.length() - 6);
						appClasses.put(typeName, entry);
					} else if (isDependencyJar(entryName)) {
						Set<String> packages = getPackages(zf.getInputStream(entry));
						for (String packageName : packages) {
							if (packageCache.get(packageName)!=null) {
								System.out.println("Split package found: "+packageName+" already in "+packageCache.get(packageName).getName()+" and now in "+entry.getName());
								System.out.println("Need to do a bit of work to support this!");
							}
							packageCache.put(packageName, entry);
						}
					}
				}
			}
		} catch (IOException ioe) {
			throw new RuntimeException("Problem during scan", ioe);
		}
	}

	private Set<String> getPackages(InputStream inputStream) {
		try {
			Set<String> packages = new HashSet<>();
			ZipInputStream zis = new ZipInputStream(inputStream);
			ZipEntry ze = zis.getNextEntry();
			while (ze != null) {
				String name = ze.getName();
				int lastSlash = name.lastIndexOf("/");
				if (lastSlash != -1 && name.endsWith(".class")) {
					packages.add(name.substring(0, lastSlash));
				}
				ze = zis.getNextEntry();
			}
			return packages;
		} catch (Exception e) {
			throw new RuntimeException("Unexpected exception computing packages from inner zip", e);
		}
	}

	private boolean isAppClass(String name) {
		return name.startsWith(APP_CLASSES_PREFIX) && name.endsWith(".class");
	}

	private boolean isDependencyJar(String name) {
		return name.startsWith(DEPENDENCIES_PREFIX) && name.endsWith(".jar");
	}

	public byte[] find(String slashedTypeName) {
		try {
			String packageName = slashedTypeName.substring(0, slashedTypeName.lastIndexOf("/"));
			ZipEntry dependencyEntry = packageCache.get(packageName);
			if (dependencyEntry != null) {
				return loadFromDepEntry(dependencyEntry, slashedTypeName);
			}
			ZipEntry appEntry = appClasses.get(slashedTypeName);
			if (appEntry != null) {
				return loadFromAppEntry(appEntry, slashedTypeName);
			}
			return null;
		} catch (IOException ioe) {
			throw new RuntimeException("Problem finding " + slashedTypeName, ioe);
		}
	}

	private byte[] loadFromAppEntry(ZipEntry dependencyEntry, String slashedTypeName) throws IOException {
		try (ZipFile zf = new ZipFile(bootJarPath)) {
			try (InputStream is = zf.getInputStream(dependencyEntry)) {
				return loadFromStream(is);
			}
		}
	}

	private byte[] loadFromDepEntry(ZipEntry dependencyEntry, String slashedTypeName) throws IOException {
		String searchName = slashedTypeName + ".class";
//		System.out.println("Loading "+searchName+" from "+dependencyEntry.getName());
		try (ZipFile zf = new ZipFile(bootJarPath)) {
			try (InputStream is = zf.getInputStream(dependencyEntry)) {
				ZipInputStream zis = new ZipInputStream(is);
				ZipEntry ze = zis.getNextEntry();
				while (ze != null) {
					String name = ze.getName();
					if (name.equals(searchName)) {
						return loadFromStream(zis);
					}
					ze = zis.getNextEntry();
				}
			}
		}
		return null;
	}

	public static byte[] loadFromStream(InputStream stream) {
		try {
			BufferedInputStream bis = new BufferedInputStream(stream);
			int size = 2048;
			byte[] theData = new byte[size];
			int dataReadSoFar = 0;
			byte[] buffer = new byte[size / 2];
			int read = 0;
			while ((read = bis.read(buffer)) != -1) {
				if ((read + dataReadSoFar) > theData.length) {
					// need to make more room
					byte[] newTheData = new byte[theData.length * 2];
					// System.out.println("doubled to " + newTheData.length);
					System.arraycopy(theData, 0, newTheData, 0, dataReadSoFar);
					theData = newTheData;
				}
				System.arraycopy(buffer, 0, theData, dataReadSoFar, read);
				dataReadSoFar += read;
			}
			bis.close();
			// Resize to actual data read
			byte[] returnData = new byte[dataReadSoFar];
			System.arraycopy(theData, 0, returnData, 0, dataReadSoFar);
			return returnData;
		} catch (IOException e) {
			throw new RuntimeException("Unexpectedly unable to load bytedata from input stream", e);
		}
	}
}
