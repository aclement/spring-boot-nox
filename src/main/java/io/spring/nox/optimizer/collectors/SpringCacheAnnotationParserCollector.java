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
public class SpringCacheAnnotationParserCollector implements Collector {

	public static Map<String,Integer> cachingAnnotationsCount;
	public static List<String> cachingAnnotationNames;

	static {
		cachingAnnotationsCount = new HashMap<>();
		cachingAnnotationsCount.put(toType("org.springframework.cache.annotation.CacheEvict"),0);
		cachingAnnotationsCount.put(toType("org.springframework.cache.annotation.Caching"),0);
		cachingAnnotationsCount.put(toType("org.springframework.cache.annotation.CachePut"),0);
		cachingAnnotationsCount.put(toType("org.springframework.cache.annotation.Cacheable"),0);
		cachingAnnotationsCount.put(toType("org.springframework.cache.annotation.CacheConfig"),0);
		cachingAnnotationNames = cachingAnnotationsCount.entrySet().stream().map(e -> e.getKey()).collect(Collectors.toList());
	}
	
	static String toType(String s) {
		return "L" + s.replace(".", "/") + ";";
	}
	
	private TypeSystem typeSystem;
	
	public void setTypeSystem(TypeSystem typeSystem) {
		this.typeSystem = typeSystem;
	}
	
	@Override
	public void processAnnotation(ClassInfo ci, ElementType type, String desc) {
		Integer count = cachingAnnotationsCount.get(desc);
		if (count!=null) {
			cachingAnnotationsCount.put(desc, count+1);
		}
		for (String cachingAnnotation: cachingAnnotationNames) {
			if (isUsedAsMetaAnnotation(typeSystem, desc, cachingAnnotation)) {
				count = cachingAnnotationsCount.get(desc);
				cachingAnnotationsCount.put(desc, count+1);
			}			
		}
	}

	@Override
	public void summarize() {
		System.out.println("SpringCacheAnnotationParserCollector");
		for (Map.Entry<String, Integer> entry: cachingAnnotationsCount.entrySet()) {
			System.out.println("Occurrences of "+toName(entry.getKey())+"=#"+entry.getValue());
		}
	}

	@Override
	public String toString() {
		return "SpringCacheAnnotationParserCollector";
	}

	@Override
	public String getPrecomputedKey() {
		return "org.springframework.cache.annotation.SpringCacheAnnotationParser"; 
	}

	public static String toName(String Lsig) {
		return Lsig.substring(1,Lsig.length()-1).replace('/', '.');
	}
	
	@Override
	public Object getPrecomputedInfo() {
		Map<String,Boolean> data = new HashMap<>();
		for (Map.Entry<String, Integer> entry: cachingAnnotationsCount.entrySet()) {
			data.put(toName(entry.getKey()), entry.getValue()!=0);
		}
		return data;
	}
}