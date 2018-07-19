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

package io.spring.nox;

import java.io.File;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import io.spring.nox.optimizer.JarOptimizer;

/**
 * @author Andy Clement
 */
@SpringBootApplication
public class NoxApplication implements ApplicationRunner {

	public static void main(String[] args) {
		SpringApplication.run(NoxApplication.class, args);
	}
	
	@Override
	public void run(ApplicationArguments args) {
		List<String> nonOptionArgs = args.getNonOptionArgs();
		if (nonOptionArgs.size()!=1) {
			System.out.println("Nox just needs the path to the jar to process");
			System.exit(1);
		}
		File inputJar = new File(nonOptionArgs.get(0));
		new JarOptimizer(inputJar).buildOptimizedVariant();
	}
}
