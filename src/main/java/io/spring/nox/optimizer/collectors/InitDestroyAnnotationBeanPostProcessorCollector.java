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

import java.lang.annotation.ElementType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.spring.nox.optimizer.ClassInfo;
import io.spring.nox.optimizer.spi.Collector;
import io.spring.nox.type.TypeSystem;

/**
 * @author Andy Clement
 */
public class InitDestroyAnnotationBeanPostProcessorCollector implements Collector {

	public static Map<String,Set<ClassInfo>> annotatedClassInfos;
	public static List<String> annotationNames;

	static {
		annotatedClassInfos = new HashMap<>();
		annotatedClassInfos.put(toType("javax.annotation.PostConstruct"),new HashSet<ClassInfo>());
		annotatedClassInfos.put(toType("javax.annotation.PreDestroy"),new HashSet<ClassInfo>());
		annotationNames = annotatedClassInfos.entrySet().stream().map(e -> e.getKey()).collect(Collectors.toList());
	}
	
	static String toType(String s) {
		return "L" + s.replace(".", "/") + ";";
	}
	
	static String toName(String Lsig) {
		return Lsig.substring(1,Lsig.length()-1).replace('/', '.');
	}
	
	private TypeSystem typeSystem;
	
	public void setTypeSystem(TypeSystem typeSystem) {
		this.typeSystem = typeSystem;
	}
	
	@Override
	public void processAnnotation(ClassInfo ci, ElementType type, String desc) {
		Set<ClassInfo> cis = annotatedClassInfos.get(desc);
		if (cis != null && !cis.contains(ci)) {
			cis.add(ci);
			return;
		}
		for (String annotationName: annotationNames) {
			if (isUsedAsMetaAnnotation(typeSystem, desc, annotationName)) { // TODO are these usable as metas?
				cis = annotatedClassInfos.get(desc);
				if (cis != null && !cis.contains(ci)) {
					cis.add(ci);
				}
			}			
		}
	}

	@Override
	public void summarize() {
		System.out.println("InitDestroyAnnotationBeanPostProcessorCollector");
		for (Map.Entry<String, Set<ClassInfo>> entry: annotatedClassInfos.entrySet()) {
			System.out.println("Occurrences of  "+toName(entry.getKey())+"=#"+entry.getValue().size()+"  "+entry.getValue());
		}
	}

	@Override
	public String getPrecomputedKey() {
		return "org.springframework.beans.factory.annotation.InitDestroyAnnotationBeanPostProcessor";
	}

	@Override
	public Object getPrecomputedInfo() {
		List<String> data = new ArrayList<>();
		for (Map.Entry<String, Set<ClassInfo>> entry: annotatedClassInfos.entrySet()) {
			for (ClassInfo ci: entry.getValue()) {
				data.add(ci.getTypeName());
			}
		}
		return data;
	}

	@Override
	public String toString() {
		return "InitDestroyAnnotationBeanPostProcessorCollector";
	}

}