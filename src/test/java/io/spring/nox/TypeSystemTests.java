//package io.spring.nox;
//
//import static org.junit.Assert.*;
//
//
//import org.junit.BeforeClass;
//import org.junit.Test;
//
//import io.spring.nox.type.Type;
//import io.spring.nox.type.TypeSystem;
//
//public class TypeSystemTests {
//
//	static TypeSystem typeSystem;
//	
//	@BeforeClass
//	public static void init() {
//		typeSystem = TypeSystem.forBootJar(... need to sort this out...);
//	}
//
//	@Test
//	public void string() {
//		Type string = typeSystem.resolveDotted("java.lang.String");
//		assertNotNull(string);
//		assertEquals("java/lang/String",string.getName());
//	}
//	
//	@Test
//	public void appClass() {
//		Type funtime = typeSystem.resolveDotted("com/example/demo/Funtime");
//		assertNotNull(funtime);
//		assertEquals("com/example/demo/Funtime",funtime.getName());
//	}
//
//	@Test
//	public void dependencyClass() {
//		Type beanFactory = typeSystem.resolveDotted("org/springframework/beans/factory/BeanFactory");
//		assertNotNull(beanFactory);
//		assertEquals("org/springframework/beans/factory/BeanFactory",beanFactory.getName());
//	}
//	
//	@Test
//	public void supertype() {
//		Type enhancedConfiguration = typeSystem.resolveDotted("org/springframework/context/annotation/ConfigurationClassEnhancer$EnhancedConfiguration");
//		assertNotNull(enhancedConfiguration);
//		assertEquals("org/springframework/context/annotation/ConfigurationClassEnhancer$EnhancedConfiguration", enhancedConfiguration.getName());
//		assertEquals("java/lang/Object", enhancedConfiguration.getSuperclass().getName());
//		assertEquals("org/springframework/beans/factory/BeanFactoryAware",enhancedConfiguration.getInterfaces()[0].getName());
//	}
//}
