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

import java.lang.reflect.Modifier;
import java.util.zip.ZipEntry;

/**
 * @author Andy Clement
 */
public class ClassInfo {

	private String name;
	private boolean configurationClass;
	private ZipEntry containingEntry;
	private int access;

	ClassInfo() {
	}

	public boolean isConfigurationClass() {
		return configurationClass;
	}

	// ---
	void setContainingEntry(ZipEntry containingEntry) {
		this.containingEntry = containingEntry;
	}
	
	public boolean isApplicationClass() {
		return containingEntry ==null;
	}

	public ZipEntry getContainingEntry() {
		return containingEntry;
	}

	void setClassName(String name) {
		this.name = name;
	}

	public String getTypeName() {
		return name;
	}

	public String toString() {
		return "[ClassInfo:" + name + "]";
	}

	void setAccess(int access) {
		this.access = access;
	}
	
	public boolean isInterface() {
		return Modifier.isInterface(access);
	}


}