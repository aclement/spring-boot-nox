# spring-boot-nox

This is an experimental boot app that post processes boot apps.  It takes a fat jar as input and gives you back a 
faster fat jar. Some places may go to far in making assumptions about Spring but remember it is just an experiment. This
isn't trying to move all of Springs dynamic behavour to post-compile/pre-run step, instead it is moving things that are
a little more set in stone and always likely to be the same at each startup (whether some type has meta annotation X).

It could easily morph into a post compile step in an IDE.

## What does it do?

First, there is a separate tool being used that measures a few things - like how much reflection happens at boot startup and
how many CGLIB classes are loaded. I'm not picking on reflection here from a 'reflection is slow' point of view (because
it isnt) but more from a 'reflection means we are digging around for info, why cant we dig around for it sooner' point of
view.

Here is a report for a simple MVC app that is using a bit of autowiring and a few configuration classes. All the
measurements here are measuring startup:

```
Number of reflective calls: #43060
Top users of reflection:
org.springframework.context.event.EventListenerMethodProcessor.lambda$processBean$0:145  #5300
org.springframework.beans.factory.annotation.InitDestroyAnnotationBeanPostProcessor.lambda$buildLifecycleMetadata$0:246  #4216
org.springframework.beans.factory.annotation.InitDestroyAnnotationBeanPostProcessor.lambda$buildLifecycleMetadata$0:254  #4216
org.springframework.context.annotation.CommonAnnotationBeanPostProcessor.lambda$buildResourceMetadata$1:431  #3911
org.springframework.context.annotation.CommonAnnotationBeanPostProcessor.lambda$buildResourceMetadata$1:454  #3911
org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor.findAutowiredAnnotation:484  #3225
org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor.lambda$determineCandidateConstructors$0:246  #2743
org.springframework.context.annotation.CommonAnnotationBeanPostProcessor.lambda$buildResourceMetadata$0:399  #2333
org.springframework.context.annotation.CommonAnnotationBeanPostProcessor.lambda$buildResourceMetadata$0:413  #2333
Number of classes loaded: #7237
Number of cglib classes loaded: #212
First few cglib types:
org/springframework/cglib/proxy/Enhancer$EnhancerKey$$KeyFactoryByCGLIB$$4ce19e8f
org/springframework/cglib/core/MethodWrapper$MethodWrapperKey$$KeyFactoryByCGLIB$$552be97a
com/example/demo/MeasurableApplication$$EnhancerBySpringCGLIB$$257037de
com/example/demo/Funtime$$EnhancerBySpringCGLIB$$25e288fb
org/springframework/boot/autoconfigure/context/PropertyPlaceholderAutoConfiguration$$EnhancerBySpringCGLIB$$98cf3f9d
org/springframework/boot/autoconfigure/websocket/servlet/WebSocketServletAutoConfiguration$TomcatWebSocketConfiguration$$EnhancerBySpringCGLIB$$10b2e253
org/springframework/boot/autoconfigure/websocket/servlet/WebSocketServletAutoConfiguration$$EnhancerBySpringCGLIB$$5907b736
org/springframework/boot/autoconfigure/web/servlet/ServletWebServerFactoryConfiguration$EmbeddedTomcat$$EnhancerBySpringCGLIB$$29b3f4e
```
It is important to understand that this is only measuring calls from spring code and when the call to reflection comes from
`AnnotatedElementUtils` then we walk a little further up the stack to see who is actually causing us to end up in
`AnnotatedElementUtils`.

Now let's run spring boot nox on this app:

``` 
.d8888. d8888b. d8888b. d888888b d8b   db  d888b      d8888b.  .d88b.   .d88b.  d888888b     d8b   db  .d88b.  db    db 
88'  YP 88  `8D 88  `8D   `88'   888o  88 88' Y8b     88  `8D .8P  Y8. .8P  Y8. `~~88~~'     888o  88 .8P  Y8. `8b  d8' 
`8bo.   88oodD' 88oobY'    88    88V8o 88 88          88oooY' 88    88 88    88    88        88V8o 88 88    88  `8bd8'  
  `Y8b. 88~~~   88`8b      88    88 V8o88 88  ooo     88~~~b. 88    88 88    88    88        88 V8o88 88    88  .dPYb.  
db   8D 88      88 `88.   .88.   88  V888 88. ~8~     88   8D `8b  d8' `8b  d8'    88        88  V888 `8b  d8' .8P  Y8. 
`8888Y' 88      88   YD Y888888P VP   V8P  Y888P      Y8888P'  `Y88P'   `Y88P'     YP        VP   V8P  `Y88P'  YP    YP 

Initializing type system based on boot jar: /Users/aclement/workspaces/devex/measurable/target/measurable-0.0.1-SNAPSHOT.jar
4 application classes
#38 dependencies containing #850 packages

Scanning boot jar for classes we want to transform...
BOOT-INF/lib/spring-boot-2.0.2.RELEASE.jar contains #602 classes (1 configuration classes)
BOOT-INF/lib/spring-boot-autoconfigure-2.0.2.RELEASE.jar contains #869 classes (284 configuration classes)
BOOT-INF/lib/spring-boot-actuator-autoconfigure-2.0.2.RELEASE.jar contains #262 classes (105 configuration classes)
BOOT-INF/lib/spring-context-5.1.9.BUILD-SNAPSHOT.jar contains #820 classes (7 configuration classes)
BOOT-INF/lib/spring-webmvc-5.1.9.BUILD-SNAPSHOT.jar contains #445 classes (1 configuration classes)
Scan complete: Found #2 application classes and #398 classes within dependencies to transform

Building new version of boot jar...
Need to rewrite this dependency BOOT-INF/lib/spring-boot-2.0.2.RELEASE.jar (rewriting 1 entries)
Need to rewrite this dependency BOOT-INF/lib/spring-boot-autoconfigure-2.0.2.RELEASE.jar (rewriting 284 entries)
Need to rewrite this dependency BOOT-INF/lib/spring-boot-actuator-autoconfigure-2.0.2.RELEASE.jar (rewriting 105 entries)
Need to rewrite this dependency BOOT-INF/lib/spring-context-5.1.9.BUILD-SNAPSHOT.jar (rewriting 7 entries)
Need to rewrite this dependency BOOT-INF/lib/spring-webmvc-5.1.9.BUILD-SNAPSHOT.jar (rewriting 1 entries)
Rewrite complete: /Users/aclement/workspaces/springloaded1/measurable/target/measurable-0.0.1-SNAPSHOT.nox.jar
7738ms
```

### What does nox actually do? 

At a high level it is a framework for tinkering with the fat jar. It creates a typesystem based on the jar content
which is capable of answering questions like 'is this type annotated (or meta-annotated) with this annotation?'.
It then follows a two step process - first it scans the entire jar to determine what it might like to do.
Then a second step rewrites the jar including optimizations to various classes and additional files to drive
more optimal spring paths.

### Ok, but what does nox actually do? :)

With that infrastructure in place it can do many things but initially it looked at configuration classes.
Instead of using CGLIB to generate generic proxies at
runtime for each configuration class, nox modifies configuration classes when it runs to introduce the necessary behaviour
we want them to have at runtime. This means there is no need for cglib proxies (or any other configuration class proxies)
at runtime. The generated code is also a bit more optimal than the form you get for a generic cglib proxy (and more work
could be done here to optimize it further).  Not only are the application configuration classes modified but also all
of those in the nested jars (so all those in the boot infrastructure). 

### Quick walkthrough of the code...

By way of example, consider the PostConstruct/PreDestroy processing in Spring Framework. The code in Spring in
[`InitDestroyAnnotationBeanPostProcessor`](https://github.com/spring-projects/spring-framework/blob/master/spring-beans/src/main/java/org/springframework/beans/factory/annotation/InitDestroyAnnotationBeanPostProcessor.java#L208) will search for these annotations - it typically won't find them on
many types. So let's precompute that.  We create the [`Collector`](https://github.com/aclement/spring-boot-nox/blob/530d575d82ce894ca9e666591294e8639e67caed/src/main/java/io/spring/nox/optimizer/spi/Collector.java) (a Nox term)
called [`InitDestroyAnnotationBeanPostProcessorCollector`](https://github.com/aclement/spring-boot-nox/blob/master/src/main/java/io/spring/nox/optimizer/collectors/InitDestroyAnnotationBeanPostProcessorCollector.java). Collectors are
 loaded by having them mentioned in [`META-INF/spring.factories`](https://github.com/aclement/spring-boot-nox/blob/master/src/main/resources/META-INF/spring.factories), 
here is the built in spring.factories:

```
io.spring.nox.optimizer.spi.Collector=\
io.spring.nox.optimizer.collectors.ConfigurationClassCollectorRewriter,\
io.spring.nox.optimizer.collectors.SpringCacheAnnotationParserCollector,\
io.spring.nox.optimizer.collectors.CommonAnnotationBeanPostProcessorCollector,\
io.spring.nox.optimizer.collectors.InitDestroyAnnotationBeanPostProcessorCollector
```

See the `InitDestroyABPPC` mentioned there. By having a collector you will be called as nox visits the boot jar classes
(application classes and classes inside dependencies). As a collector you can choose what you are interested in, so
our InitDestroyABPPC, in [`processAnnotation`](https://github.com/aclement/spring-boot-nox/blob/master/src/main/java/io/spring/nox/optimizer/collectors/InitDestroyAnnotationBeanPostProcessorCollector.java#L61) it checks for PostConstruct/PreDestroy and records where it was found.
Because there is a type system in existence (driven by the boot jar contents) it is possible to use that for
deeper analysis, for example if you need to check if the annotation you have been passed is meta-annotated by those
you are interested in.

Once the initial scan is finished, you be be called for a precomputed key and value. The key will identify your
data in the precomputed blob passed to Spring (the current examples use the class that will be paying attention
to the data from this collector as the key).  Our InitDestroyABPPC key is [`org.springframework.beans.factory.annotation.InitDestroyAnnotationBeanPostProcessor`](https://github.com/aclement/spring-boot-nox/blob/master/src/main/java/io/spring/nox/optimizer/collectors/InitDestroyAnnotationBeanPostProcessorCollector.java#L86)
The precomputed value will typically be a map or a list of simple data types. In the case of InitDestroyABPPC
it is a list of the types that have one or more of the PostConstruct/PreDestroy somewhere in them.

After fetching this data from the collector it is generated into a class (`PrecomputedInfoLoader`) which is
added to the jar. 

Obviously this precomputed data is only useful if spring is looking out for it, hence you need to be
running the spring fork currently at `https://github.com/aclement/spring-framework/tree/lazy-conversion-service`. 
This is a 5.1.0.BUILD-SNAPSHOT currently so you need to
build that spring locally `./gradlew install` and then override your spring version to use it.
When that version of spring starts, one central attempt is made to load the
PrecomputedInfoLoader class (if not found, no big deal) - if found it is called to initialize
a map and then the various classes who want to use it can access it for their own precomputed data. Here is the
variant of [`InitDestroyAnnotationBeanPostProcessor`](https://github.com/aclement/spring-framework/blob/lazy-conversion-service/spring-beans/src/main/java/org/springframework/beans/factory/annotation/InitDestroyAnnotationBeanPostProcessor.java#L96) in the spring-framework fork that loads the precomputed data.

### Does it help?

There are few aspects to consider: impact on startup, impact on memory profile and impact on those reflection numbers 
we were looking at...

Benchmarks, using Daves benchmark framework comparing measurable regular vs nox:

```
Benchmark                                  Mode  Cnt  Score   Error  Units
MeasurableBenchmark.explodedJarMain        avgt   18  1.574 ± 0.022   s/op
MeasurableBenchmark.fatJar                 avgt   18  1.933 ± 0.023   s/op
MeasurableNoxBenchmark.explodedJarMain     avgt   18  1.403 ± 0.024   s/op
MeasurableNoxBenchmark.fatJar              avgt   18  1.768 ± 0.031   s/op
```

Bit of improvement there. What about those other stats?

```
Number of reflective calls: #37609
Top sources of reflection:
org.springframework.context.event.EventListenerMethodProcessor.lambda$processBean$0:145  #5545
org.springframework.beans.factory.annotation.InitDestroyAnnotationBeanPostProcessor.lambda$buildLifecycleMetadata$0:246  #3469
org.springframework.beans.factory.annotation.InitDestroyAnnotationBeanPostProcessor.lambda$buildLifecycleMetadata$0:254  #3469
org.springframework.context.annotation.CommonAnnotationBeanPostProcessor.lambda$buildResourceMetadata$1:431  #3296
org.springframework.context.annotation.CommonAnnotationBeanPostProcessor.lambda$buildResourceMetadata$1:454  #3296
org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor.findAutowiredAnnotation:484  #3225
Number of classes loaded: #7031
Number of cglib classes loaded: #5
First few cglib types:
org/springframework/cglib/proxy/Enhancer$EnhancerKey$$KeyFactoryByCGLIB$$4ce19e8f
org/springframework/cglib/core/MethodWrapper$MethodWrapperKey$$KeyFactoryByCGLIB$$552be97a
org/springframework/boot/autoconfigure/http/HttpMessageConverters$$EnhancerBySpringCGLIB$$1d90bff9
org/springframework/boot/autoconfigure/http/HttpMessageConverters$$FastClassBySpringCGLIB$$d5af8918
org/springframework/boot/autoconfigure/http/HttpMessageConverters$$EnhancerBySpringCGLIB$$1d90bff9$$FastClassBySpringCGLIB$$8fc601a2
```

CGLIB now only being used for something else, not config classes. Number of classes loaded reduced by 200 as expected 
(obviously the ones we are loading for config are a little bigger). Worth noting that is also not running against a 
vanilla Spring 5 - this is 5.1 with a few (very few) tweaks to handle the augmented config classes. ~6000 calls 
to reflection gone. Not bad, so what is left in the reflection list?

`InitDestroyAnnotationBeanPostProcessor` making ~7000 calls. That is looking for PreDestroy and PostConstruct annotations
(usually - I say usually because these things seem configurable - for the sake of experimenting here I'm 'assuming'
the default case). As an experiment let's precompute the types that have them on and via a static initializer in the 
InitDestroyAnnotationBeanPostProcessor initialize a cache - so another spring tweak but effectively if the cache isn't
initialized Spring will do what it always does (go hunting) but if initialized it knows which classes it is worth looking
in:

```
.d8888. d8888b. d8888b. d888888b d8b   db  d888b      d8888b.  .d88b.   .d88b.  d888888b     d8b   db  .d88b.  db    db 
88'  YP 88  `8D 88  `8D   `88'   888o  88 88' Y8b     88  `8D .8P  Y8. .8P  Y8. `~~88~~'     888o  88 .8P  Y8. `8b  d8' 
`8bo.   88oodD' 88oobY'    88    88V8o 88 88          88oooY' 88    88 88    88    88        88V8o 88 88    88  `8bd8'  
  `Y8b. 88~~~   88`8b      88    88 V8o88 88  ooo     88~~~b. 88    88 88    88    88        88 V8o88 88    88  .dPYb.  
db   8D 88      88 `88.   .88.   88  V888 88. ~8~     88   8D `8b  d8' `8b  d8'    88        88  V888 `8b  d8' .8P  Y8. 
`8888Y' 88      88   YD Y888888P VP   V8P  Y888P      Y8888P'  `Y88P'   `Y88P'     YP        VP   V8P  `Y88P'  YP    YP 

...same as before...
PreDestroy/PostConstruct related annotations found in #21 classes
...same as before...
```

Speed?
```
Benchmark                               Mode  Cnt  Score   Error  Units
MeasurableBenchmark.explodedJarMain        avgt   18  1.566 ± 0.022   s/op
MeasurableBenchmark.fatJar                 avgt   18  1.920 ± 0.017   s/op
MeasurableNoxBenchmark.explodedJarMain     avgt   18  1.393 ± 0.022   s/op
MeasurableNoxBenchmark.fatJar              avgt   18  1.725 ± 0.012   s/op
```

```
Reflection Summary
Number of reflective calls: #30681
Top sources of reflection:
org.springframework.context.event.EventListenerMethodProcessor.lambda$processBean$0:145  #5539
org.springframework.context.annotation.CommonAnnotationBeanPostProcessor.lambda$buildResourceMetadata$1:431  #3298
org.springframework.context.annotation.CommonAnnotationBeanPostProcessor.lambda$buildResourceMetadata$1:454  #3298
org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor.findAutowiredAnnotation:484  #3225
org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor.lambda$determineCandidateConstructors$0:246  #1996
org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.isHandler:205  #1771
org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.isHandler:206  #1769
org.springframework.core.annotation.AnnotationUtils.hasSearchableAnnotations:639  #1518
org.springframework.context.annotation.CommonAnnotationBeanPostProcessor.lambda$buildResourceMetadata$0:399  #1378
org.springframework.context.annotation.CommonAnnotationBeanPostProcessor.lambda$buildResourceMetadata$0:413  #1378
org.springframework.core.annotation.AnnotationUtils.getAnnotations:244  #976
Number of classes loaded: #7030
Number of cglib classes loaded: #5
First few cglib types:
org/springframework/cglib/proxy/Enhancer$EnhancerKey$$KeyFactoryByCGLIB$$4ce19e8f
org/springframework/cglib/core/MethodWrapper$MethodWrapperKey$$KeyFactoryByCGLIB$$552be97a
org/springframework/boot/autoconfigure/http/HttpMessageConverters$$EnhancerBySpringCGLIB$$1d90bff9
org/springframework/boot/autoconfigure/http/HttpMessageConverters$$FastClassBySpringCGLIB$$d5af8918
org/springframework/boot/autoconfigure/http/HttpMessageConverters$$EnhancerBySpringCGLIB$$1d90bff9$$FastClassBySpringCGLIB$$8fc601a2
```

Another 6000 reflection calls gone. Ok that's a tiny app, let's try something else, boot petclinic:

Here is the raw reflection result for petclinic:

```
Number of reflective calls: #414372
Top sources of reflection:
org.springframework.cache.annotation.SpringCacheAnnotationParser.getDefaultCacheConfig:264  #57666
org.springframework.cache.annotation.SpringCacheAnnotationParser.parseCacheAnnotations:120  #35782
org.springframework.cache.annotation.SpringCacheAnnotationParser.parseCacheAnnotations:129  #35782
org.springframework.cache.annotation.SpringCacheAnnotationParser.parseCacheAnnotations:138  #35782
org.springframework.cache.annotation.SpringCacheAnnotationParser.parseCacheAnnotations:147  #35782
org.springframework.transaction.annotation.SpringTransactionAnnotationParser.parseTransactionAnnotation:44  #34771
org.springframework.transaction.annotation.JtaTransactionAnnotationParser.parseTransactionAnnotation:45  #23525
org.springframework.cache.jcache.interceptor.AnnotationJCacheOperationSource.findCacheOperation:50  #15460
org.springframework.cache.jcache.interceptor.AnnotationJCacheOperationSource.findCacheOperation:51  #15460
org.springframework.cache.jcache.interceptor.AnnotationJCacheOperationSource.findCacheOperation:52  #15460
org.springframework.cache.jcache.interceptor.AnnotationJCacheOperationSource.findCacheOperation:53  #15460
org.springframework.context.event.EventListenerMethodProcessor.lambda$processBean$0:145  #7655
org.springframework.orm.jpa.support.PersistenceAnnotationBeanPostProcessor.lambda$buildPersistenceMetadata$1:425  #7033
org.springframework.orm.jpa.support.PersistenceAnnotationBeanPostProcessor.lambda$buildPersistenceMetadata$1:426  #7029
org.springframework.context.annotation.CommonAnnotationBeanPostProcessor.lambda$buildResourceMetadata$1:431  #6457
org.springframework.context.annotation.CommonAnnotationBeanPostProcessor.lambda$buildResourceMetadata$1:454  #6457
org.springframework.beans.factory.annotation.InitDestroyAnnotationBeanPostProcessor.lambda$buildLifecycleMetadata$0:246  #6419
org.springframework.beans.factory.annotation.InitDestroyAnnotationBeanPostProcessor.lambda$buildLifecycleMetadata$0:254  #6419
Number of classes loaded: #12273
Number of cglib classes loaded: #311
First few cglib types:
org/springframework/cglib/proxy/Enhancer$EnhancerKey$$KeyFactoryByCGLIB$$4ce19e8f
org/springframework/cglib/core/MethodWrapper$MethodWrapperKey$$KeyFactoryByCGLIB$$552be97a
org/springframework/samples/petclinic/PetClinicApplication$$EnhancerBySpringCGLIB$$68caf576
org/springframework/samples/petclinic/system/CacheConfiguration$$EnhancerBySpringCGLIB$$4cbbf97a
org/springframework/cache/annotation/ProxyCachingConfiguration$$EnhancerBySpringCGLIB$$89fd0113
org/springframework/cache/jcache/config/ProxyJCacheConfiguration$$EnhancerBySpringCGLIB$$63328d73
org/springframework/boot/autoconfigure/context/MessageSourceAutoConfiguration$$EnhancerBySpringCGLIB$$886b8aca
org/springframework/boot/autoconfigure/context/PropertyPlaceholderAutoConfiguration$$EnhancerBySpringCGLIB$$41a13d2e
org/springframework/boot/autoconfigure/websocket/servlet/WebSocketServletAutoConfiguration$TomcatWebSocketConfiguration$$EnhancerBySpringCGLIB$$b984dfe4
org/springframework/boot/autoconfigure/websocket/servlet/WebSocketServletAutoConfiguration$$EnhancerBySpringCGLIB$$1d9b4c7
org/springframework/boot/autoconfigure/web/servlet/ServletWebServerFactoryConfiguration$EmbeddedTomcat$$EnhancerBySpringCGLIB$$ab6d3cdf
```

Serious stuff, 400k calls to reflection. Let's nox it:

```
.d8888. d8888b. d8888b. d888888b d8b   db  d888b      d8888b.  .d88b.   .d88b.  d888888b     d8b   db  .d88b.  db    db 
88'  YP 88  `8D 88  `8D   `88'   888o  88 88' Y8b     88  `8D .8P  Y8. .8P  Y8. `~~88~~'     888o  88 .8P  Y8. `8b  d8' 
`8bo.   88oodD' 88oobY'    88    88V8o 88 88          88oooY' 88    88 88    88    88        88V8o 88 88    88  `8bd8'  
  `Y8b. 88~~~   88`8b      88    88 V8o88 88  ooo     88~~~b. 88    88 88    88    88        88 V8o88 88    88  .dPYb.  
db   8D 88      88 `88.   .88.   88  V888 88. ~8~     88   8D `8b  d8' `8b  d8'    88        88  V888 `8b  d8' .8P  Y8. 
`8888Y' 88      88   YD Y888888P VP   V8P  Y888P      Y8888P'  `Y88P'   `Y88P'     YP        VP   V8P  `Y88P'  YP    YP 

Initializing type system based on boot jar: /Users/aclement/workspaces/devex/spring-petclinic/target/spring-petclinic-2.0.0.BUILD-SNAPSHOT.jar
#25 application classes
#65 dependencies containing #1596 packages

Scanning boot jar for classes we want to transform...
BOOT-INF/lib/spring-boot-actuator-autoconfigure-2.0.3.RELEASE.jar contains #266 classes (108 configuration classes)
BOOT-INF/lib/spring-context-5.1.9.BUILD-SNAPSHOT.jar contains #820 classes (7 configuration classes)
BOOT-INF/lib/spring-context-support-5.1.9.BUILD-SNAPSHOT.jar contains #117 classes (2 configuration classes)
BOOT-INF/lib/spring-data-commons-2.0.8.RELEASE.jar contains #709 classes (4 configuration classes)
BOOT-INF/lib/spring-tx-5.1.9.BUILD-SNAPSHOT.jar contains #215 classes (2 configuration classes)
BOOT-INF/lib/spring-aspects-5.1.9.BUILD-SNAPSHOT.jar contains #27 classes (5 configuration classes)
BOOT-INF/lib/spring-webmvc-5.1.9.BUILD-SNAPSHOT.jar contains #445 classes (1 configuration classes)
BOOT-INF/lib/spring-boot-2.0.3.RELEASE.jar contains #603 classes (1 configuration classes)
BOOT-INF/lib/spring-boot-autoconfigure-2.0.3.RELEASE.jar contains #870 classes (285 configuration classes)
Scan complete: Found #2 application classes and #415 classes within dependencies to transform

Building new version of boot jar...
Need to rewrite this dependency BOOT-INF/lib/spring-boot-actuator-autoconfigure-2.0.3.RELEASE.jar (rewriting 108 entries)
Need to rewrite this dependency BOOT-INF/lib/spring-context-5.1.9.BUILD-SNAPSHOT.jar (rewriting 7 entries)
Need to rewrite this dependency BOOT-INF/lib/spring-context-support-5.1.9.BUILD-SNAPSHOT.jar (rewriting 2 entries)
Need to rewrite this dependency BOOT-INF/lib/spring-data-commons-2.0.8.RELEASE.jar (rewriting 4 entries)
Need to rewrite this dependency BOOT-INF/lib/spring-tx-5.1.9.BUILD-SNAPSHOT.jar (rewriting 2 entries)
Need to rewrite this dependency BOOT-INF/lib/spring-aspects-5.1.9.BUILD-SNAPSHOT.jar (rewriting 5 entries)
Need to rewrite this dependency BOOT-INF/lib/spring-webmvc-5.1.9.BUILD-SNAPSHOT.jar (rewriting 1 entries)
Need to rewrite this dependency BOOT-INF/lib/spring-boot-2.0.3.RELEASE.jar (rewriting 1 entries)
Need to rewrite this dependency BOOT-INF/lib/spring-boot-autoconfigure-2.0.3.RELEASE.jar (rewriting 285 entries)
PreDestroy/PostConstruct related annotations found in #22 classes
Rewrite complete: /Users/aclement/gits/2/spring-petclinic/target/spring-petclinic-2.0.0.BUILD-SNAPSHOT.nox.jar
9328ms
```

Speed?

```
Benchmark                                  Mode  Cnt  Score   Error  Units
PetclinicAndyBenchmark.explodedJarMain     avgt   18  3.288 ± 0.032   s/op
PetclinicAndyBenchmark.fatJar              avgt   18  4.653 ± 0.031   s/op
PetclinicAndyNoxBenchmark.explodedJarMain  avgt   18  3.122 ± 0.021   s/op
PetclinicAndyNoxBenchmark.fatJar           avgt   18  4.445 ± 0.026   s/op
```

Reflection?

```
Reflection Summary
Number of reflective calls: #447625
Top sources of reflection:
org.springframework.cache.annotation.SpringCacheAnnotationParser.getDefaultCacheConfig:264  #70384
org.springframework.cache.annotation.SpringCacheAnnotationParser.parseCacheAnnotations:120  #42779
org.springframework.cache.annotation.SpringCacheAnnotationParser.parseCacheAnnotations:129  #42779
org.springframework.cache.annotation.SpringCacheAnnotationParser.parseCacheAnnotations:138  #42779
org.springframework.cache.annotation.SpringCacheAnnotationParser.parseCacheAnnotations:147  #42779
org.springframework.transaction.annotation.SpringTransactionAnnotationParser.parseTransactionAnnotation:44  #42058
org.springframework.transaction.annotation.JtaTransactionAnnotationParser.parseTransactionAnnotation:45  #30119
org.springframework.cache.jcache.interceptor.AnnotationJCacheOperationSource.findCacheOperation:50  #16107
org.springframework.cache.jcache.interceptor.AnnotationJCacheOperationSource.findCacheOperation:51  #16107
org.springframework.cache.jcache.interceptor.AnnotationJCacheOperationSource.findCacheOperation:52  #16107
org.springframework.cache.jcache.interceptor.AnnotationJCacheOperationSource.findCacheOperation:53  #16107
org.springframework.context.event.EventListenerMethodProcessor.lambda$processBean$0:145  #8053
org.springframework.orm.jpa.support.PersistenceAnnotationBeanPostProcessor.lambda$buildPersistenceMetadata$1:425  #5939
org.springframework.orm.jpa.support.PersistenceAnnotationBeanPostProcessor.lambda$buildPersistenceMetadata$1:426  #5935
org.springframework.context.annotation.CommonAnnotationBeanPostProcessor.lambda$buildResourceMetadata$1:431  #5524
org.springframework.context.annotation.CommonAnnotationBeanPostProcessor.lambda$buildResourceMetadata$1:454  #5524
org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor.findAutowiredAnnotation:484  #4723
org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor.lambda$determineCandidateConstructors$0:246  #3928
org.springframework.core.annotation.AnnotationUtils.hasSearchableAnnotations:639  #2726
org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.isHandler:205  #2672
Number of classes loaded: #11969
Number of cglib classes loaded: #5
First few cglib types:
org/springframework/cglib/proxy/Enhancer$EnhancerKey$$KeyFactoryByCGLIB$$4ce19e8f
org/springframework/cglib/core/MethodWrapper$MethodWrapperKey$$KeyFactoryByCGLIB$$552be97a
org/springframework/boot/autoconfigure/http/HttpMessageConverters$$EnhancerBySpringCGLIB$$1d90bff9
org/springframework/boot/autoconfigure/http/HttpMessageConverters$$FastClassBySpringCGLIB$$d5af8918
org/springframework/boot/autoconfigure/http/HttpMessageConverters$$EnhancerBySpringCGLIB$$1d90bff9$$FastClassBySpringCGLIB$$8fc601a2
```

What? the classload count is down (and no more CGLIB for config classes) but the reflection count is up? 414k>447k - why?

The new augmented configuration classes have a couple of extra members (that are normally hidden in the proxies). It 
is as if some bit of spring does a vast amount of reflection and if we add one member it does a surprisingly huge amount
more because of that member. One potential candidate is indicated in that list above in `SpringCacheAnnotationParser` (the
line numbers refer to a modded spring so won't quite match up in framework master) there is code like:

```
Collection<Cacheable> cacheables = (localOnly ? AnnotatedElementUtils.getAllMergedAnnotations(ae, Cacheable.class) :
  AnnotatedElementUtils.findAllMergedAnnotations(ae, Cacheable.class));
```

This is repeated for four types of annotation. There is also `getDefaultCacheConfig` that reflects on the classes. Can nox help?
We could pre-compute of where these things are but instead let's demonstrate another technique. Let's use nox to see
if the 5 annotations in question are in use anywhere and pass that info to the SpringCacheAnnotationParser. So we don't
remember where they are (because Spring is doing special digging around with AnnotatedMethodUtils when hunting for them
and I don't want to mess about with that) instead we just remember whether there are any of that annotation anywhere.
For Petclinic, for example, there is only one use of @Cacheable - no uses of the others. Here's new nox:

```
...
Any caching? true[@Cacheable]
...
```

And reflection output:

```
Number of reflective calls: #248959
Top sources of reflection:
org.springframework.cache.annotation.SpringCacheAnnotationParser.parseCacheAnnotations:131  #42779
org.springframework.transaction.annotation.SpringTransactionAnnotationParser.parseTransactionAnnotation:44  #42058
org.springframework.transaction.annotation.JtaTransactionAnnotationParser.parseTransactionAnnotation:45  #30119
org.springframework.cache.jcache.interceptor.AnnotationJCacheOperationSource.findCacheOperation:50  #16107
org.springframework.cache.jcache.interceptor.AnnotationJCacheOperationSource.findCacheOperation:51  #16107
org.springframework.cache.jcache.interceptor.AnnotationJCacheOperationSource.findCacheOperation:52  #16107
org.springframework.cache.jcache.interceptor.AnnotationJCacheOperationSource.findCacheOperation:53  #16107
org.springframework.context.event.EventListenerMethodProcessor.lambda$processBean$0:145  #8050
Number of classes loaded: #11960
Number of cglib classes loaded: #5
First few cglib types:
org/springframework/cglib/proxy/Enhancer$EnhancerKey$$KeyFactoryByCGLIB$$4ce19e8f
org/springframework/cglib/core/MethodWrapper$MethodWrapperKey$$KeyFactoryByCGLIB$$552be97a
org/springframework/boot/autoconfigure/http/HttpMessageConverters$$EnhancerBySpringCGLIB$$1d90bff9
org/springframework/boot/autoconfigure/http/HttpMessageConverters$$FastClassBySpringCGLIB$$d5af8918
org/springframework/boot/autoconfigure/http/HttpMessageConverters$$EnhancerBySpringCGLIB$$1d90bff9$$FastClassBySpringCGLIB$$8fc601a2
```

That saved 200,000 calls to reflection. Worth it speed wise?

```
Benchmark                                  Mode  Cnt  Score   Error  Units
PetclinicAndyBenchmark.explodedJarMain     avgt   18  3.282 ± 0.022   s/op
PetclinicAndyBenchmark.fatJar              avgt   18  4.636 ± 0.021   s/op
PetclinicAndyNoxBenchmark.explodedJarMain  avgt   18  3.119 ± 0.021   s/op
PetclinicAndyNoxBenchmark.fatJar           avgt   18  4.450 ± 0.023   s/op
```

Interesting that it saves so little time 

I haven't found time to look at the memory profile yet. It could be interesting as these shortcuts should reduce the
amount of transient garbage created in many places (whilst hunting for annotations).

Crude look at GC statements.

After starting regular petclinic (first GC message after 'Started PetClinicApplication' message):

```
[GC (Allocation Failure) [PSYoungGen: 780800K->13195K(968704K)] 812806K->45209K(1167872K), 0.0121372 secs] [Times: user=0.04 sys=0.01, real=0.01 secs]
```

After starting nox petclinic:

```
[GC (Allocation Failure) [PSYoungGen: 653312K->11279K(673792K)] 687665K->45640K(900608K), 0.0129719 secs] [Times: user=0.06 sys=0.01, real=0.02 secs]
```

The heap sizes being shown post GC 968704k vs 673792k  and 1167872k vs 900608k seem encouraging (don't they?)

