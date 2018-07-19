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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.spring.nox.optimizer.ClassInfo;
import io.spring.nox.optimizer.spi.Collector;
import io.spring.nox.type.TypeSystem;

/**
 * @author Andy Clement
 */
public class CommonAnnotationBeanPostProcessorCollector implements Collector {

	public static Map<String,Integer> annotationCounts;
	public static List<String> annotationNames;

	static {
		annotationCounts = new HashMap<>();
		annotationCounts.put(toType("javax.xml.ws.WebServiceRef"),0);
		annotationCounts.put(toType("javax.ejb.EJB"),0);
		annotationCounts.put(toType("javax.annotation.Resource"),0);
		annotationNames = annotationCounts.entrySet().stream().map(e -> e.getKey()).collect(Collectors.toList());
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
		Integer count = annotationCounts.get(desc);
		if (count!=null) {
			annotationCounts.put(desc, count+1);
		}
		for (String annotationName: annotationNames) {
			if (isUsedAsMetaAnnotation(typeSystem, desc, annotationName)) { // TODO are these usable as metas?
				count = annotationCounts.get(desc);
				annotationCounts.put(desc, count+1);
			}			
		}
	}

	@Override
	public void summarize() {
		System.out.println("CommonAnnotationBeanPostProcessorCollector");
		for (Map.Entry<String, Integer> entry: annotationCounts.entrySet()) {
			System.out.println("Occurrences of "+toName(entry.getKey())+"=#"+entry.getValue());
		}
	}

	@Override
	public String toString() {
		return "CommonAnnotationBeanPostProcessorCollector";
	}

	@Override
	public String getPrecomputedKey() {
		return "org.springframework.context.annotation.CommonAnnotationBeanPostProcessor";
	}

	@Override
	public Object getPrecomputedInfo() {
		boolean hasAny = false;
		for (Map.Entry<String, Integer> entry: annotationCounts.entrySet()) {
			if (entry.getValue()>0) {
				hasAny = true;
				break;
			}
		}
		return hasAny;
	}
}