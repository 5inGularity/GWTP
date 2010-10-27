/**
 * Copyright 2010 ArcBees Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.gwtplatform.tester.mockito;

import com.google.inject.Key;
import com.google.inject.Provider;
import com.google.inject.Scope;
import com.google.inject.TypeLiteral;
import com.google.inject.binder.AnnotatedBindingBuilder;
import com.google.inject.binder.LinkedBindingBuilder;
import com.google.inject.binder.ScopedBindingBuilder;
import com.google.inject.internal.Errors;
import com.google.inject.internal.ErrorsException;
import com.google.inject.spi.Dependency;
import com.google.inject.spi.InjectionPoint;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A guice {@link com.google.inject.Module Module} with a bit of syntactic sugar to bind within
 * typical test scopes. Depends on mockito. This module automatically mocks any interface or
 * abstract class dependency for which a binding is not explicitly provided. Any concrete class
 * for which a binding is not explicitly provided is bound as a {@link TestScope#SINGLETON}.
 * <p />
 * Depends on Mockito.
 * 
 * @author Philippe Beaudoin
 */
public abstract class AutomockingModule extends TestModule {

  private Set<TypeLiteral<?>> forceMock = new HashSet<TypeLiteral<?>>();
  private List<BindingInfo> bindingsObserved = new ArrayList<BindingInfo>();

  /**
   * By default, only abstract classes, interfaces and classes annotated with
   * {@link TestMockSingleton} are automatically mocked. Use {@link #forceMock} 
   * to indicate that a given concrete class type should be mocked.  
   * 
   * @param klass The {@link Class} to force mock
   */
  protected void forceMock(Class<?> klass) {
    forceMock.add(TypeLiteral.get(klass));
  }

  /**
   * By default, only abstract classes, interfaces and classes annotated with
   * {@link TestMockSingleton} are automatically mocked. Use {@link #forceMock} 
   * to indicate that a given concrete class type should be mocked.  
   * 
   * @param type The {@link TypeLiteral} to force mock
   */
  protected void forceMock(TypeLiteral<?> type) {
    forceMock.add(type);
  }

  @SuppressWarnings("unchecked")
  public final void configure() {
    configureTest();
    Set<Key<?>> keysObserved = new HashSet<Key<?>>(bindingsObserved.size());
    Set<Key<?>> keysNeeded = new HashSet<Key<?>>(bindingsObserved.size());

    for (BindingInfo bindingInfo : bindingsObserved) {
      if (bindingInfo.annotation != null) {
        keysObserved.add(Key.get(bindingInfo.abstractType, bindingInfo.annotation));
      } else {
        keysObserved.add(Key.get(bindingInfo.abstractType));
      }
    }

    // Preempt JIT binding by looking through the test class and any base class
    // looking for nested classes annotated with @TestSingleton and @TestEagerSingleton
    Class<?> currentClass = testClass;
    while (currentClass != null) {
      for (Class<?> subClass : testClass.getDeclaredClasses()) {
        Key key = Key.get(subClass);
        if (!keysObserved.contains(key)) {
          if (subClass.isAnnotationPresent(TestSingleton.class)) {
            bind(subClass).in(TestScope.SINGLETON);
            keysObserved.add(key);
          } else if (subClass.isAnnotationPresent(TestEagerSingleton.class)) {
            bind(subClass).in(TestScope.EAGER_SINGLETON);
            keysObserved.add(key);
          } else if (subClass.isAnnotationPresent(TestMockSingleton.class)) {
            bindMock(subClass).in(TestScope.SINGLETON);
            keysObserved.add(key);
          }
        }
      }
      currentClass = currentClass.getSuperclass();
    }

    // Preempt JIT binding by looking through the test class looking for
    // methods annotated with @Test, @Before, or @After
    currentClass = testClass;
    while (currentClass != null) {
      for (Method method : currentClass.getDeclaredMethods()) {
        if (method.isAnnotationPresent(Test.class) ||
            method.isAnnotationPresent(Before.class) ||
            method.isAnnotationPresent(After.class)) {

          Errors errors = new Errors(method);
          List<Key<?>> keys = GuiceUtils.getMethodKeys(method, errors);

          for (Key<?> key : keys) {
            // Skip keys annotated with @All
            if (!All.class.equals(key.getAnnotationType())) {
              Key<?> keyNeeded = GuiceUtils.ensureProvidedKey(key, errors);
              addNeededKey(keysObserved, keysNeeded, keyNeeded);
            }
          }

          errors.throwConfigurationExceptionIfErrorsExist();
        }
      }
      currentClass = currentClass.getSuperclass();
    }

    // Preempt JIT binding by looking through the test class looking for
    // fields and methods annotated with @Inject
    Set<InjectionPoint> injectionPoints = InjectionPoint.forInstanceMethodsAndFields(testClass);
    for (InjectionPoint injectionPoint : injectionPoints) {
      Errors errors = new Errors(injectionPoint);
      List<Dependency<?>> dependencies = injectionPoint.getDependencies();
      for (Dependency dependency : dependencies) {
        Key<?> keyNeeded = GuiceUtils.ensureProvidedKey(dependency.getKey(), errors);
        addNeededKey(keysObserved, keysNeeded, keyNeeded);
      }
      errors.throwConfigurationExceptionIfErrorsExist();
    }

    // Recursively add the dependencies of all the bindings observed
    for (int i = 0; i < bindingsObserved.size(); ++i) {
      BindingInfo bindingInfo = bindingsObserved.get(i);
      if (!bindingInfo.isBoundToInstance) {
        if (bindingInfo.boundType != null) {
          addDependencies(Key.get(bindingInfo.boundType), keysObserved, keysNeeded);
        } else {
          addDependencies(Key.get(bindingInfo.abstractType), keysObserved, keysNeeded);
        }
      }
    }    

    // Bind all keys needed but not observed as mocks
    for (Key<?> key : keysNeeded) {
      if (!keysObserved.contains(key)) {
        super.bind(key).toProvider(new MockProvider(key.getTypeLiteral().getRawType())).in(TestScope.SINGLETON);
      }
    }
  }

  private void addNeededKey(Set<Key<?>> keysObserved, Set<Key<?>> keysNeeded,
      Key<?> keyNeeded) {
    keysNeeded.add(keyNeeded);
    bindIfConcrete(keysObserved, keyNeeded);
  }
  private void bindIfConcrete(
      Set<Key<?>> keysObserved, Key<?> key) {
    TypeLiteral<?> parameter = key.getTypeLiteral();
    if (isInstantiable(parameter.getRawType()) &&
        !forceMock.contains(parameter) &&
        !keysObserved.contains(key)) {
      bind(key).in(TestScope.SINGLETON);
      keysObserved.add(key);            
    }
  }

  private boolean isInstantiable(Class<?> klass) {
    return !klass.isInterface() && !Modifier.isAbstract(klass.getModifiers());
  }

  @Override
  protected <T> LinkedBindingBuilder<T> bind(Key<T> key) {    
    return new SpyLinkedBindingBuilder<T>(newBindingObserved(key), super.bind(key));
  }

  @Override
  protected <T> AnnotatedBindingBuilder<T> bind(TypeLiteral<T> typeLiteral) {
    return new SpyAnnotatedBindingBuilder<T>(newBindingObserved(typeLiteral), super.bind(typeLiteral));
  }

  @Override
  protected <T> AnnotatedBindingBuilder<T> bind(Class<T> clazz) {
    return new SpyAnnotatedBindingBuilder<T>(newBindingObserved(clazz), super.bind(clazz));
  }

  private BindingInfo newBindingObserved(Key<?> key) {
    BindingInfo bindingInfo = new BindingInfo();
    bindingInfo.abstractType = key.getTypeLiteral();
    bindingInfo.annotation = key.getAnnotation();
    bindingsObserved.add(bindingInfo);
    return bindingInfo;
  }

  private BindingInfo newBindingObserved(TypeLiteral<?> typeLiteral) {
    BindingInfo bindingInfo = new BindingInfo();
    bindingInfo.abstractType = typeLiteral;
    bindingsObserved.add(bindingInfo);
    return bindingInfo;
  }

  private BindingInfo newBindingObserved(Class<?> clazz) {
    BindingInfo bindingInfo = new BindingInfo();
    bindingInfo.abstractType = TypeLiteral.get(clazz);
    bindingsObserved.add(bindingInfo);
    return bindingInfo;
  }

  private <T> void addDependencies(Key<T> key, Set<Key<?>> keysObserved, Set<Key<?>> keysNeeded) {
    TypeLiteral<T> type = key.getTypeLiteral();
    if (!isInstantiable(type.getRawType())) {
        return;
    }
    addInjectionPointDependencies(InjectionPoint.forConstructorOf(type),
        keysObserved, keysNeeded);
    Set<InjectionPoint> methodsAndFieldsInjectionPoints = 
      InjectionPoint.forInstanceMethodsAndFields(type);
    for (InjectionPoint injectionPoint : methodsAndFieldsInjectionPoints) {      
      addInjectionPointDependencies(injectionPoint, keysObserved, keysNeeded);
    }
  }

  private void addInjectionPointDependencies(InjectionPoint injectionPoint, Set<Key<?>> keysObserved, Set<Key<?>> keysNeeded) {
    // Do not consider dependencies coming from optional injections
    if (injectionPoint.isOptional()) {
      return;
    }
    for (Dependency<?> dependency : injectionPoint.getDependencies()) {
      Key<?> key = dependency.getKey();
      addKeyDependency(key, keysObserved, keysNeeded);
    }
  }

  private void addKeyDependency(Key<?> key, Set<Key<?>> keysObserved, Set<Key<?>> keysNeeded) {
    Key<?> newKey = key;
    if (Provider.class.isAssignableFrom(key.getTypeLiteral().getRawType())) {
      Type providedType = ((ParameterizedType) key.getTypeLiteral().getType()).getActualTypeArguments()[0];
      Annotation annotation = key.getAnnotation();
      if (annotation == null) {
        newKey = Key.get(providedType);
      } else {
        newKey = Key.get(providedType, annotation);
      }
    }
    bindIfConcrete(keysObserved, newKey);
    keysNeeded.add(newKey);
  }  

  private static class SpyLinkedBindingBuilder<T> implements LinkedBindingBuilder<T> {

    protected final BindingInfo bindingInfo;
    private final LinkedBindingBuilder<T> delegate;

    public SpyLinkedBindingBuilder(BindingInfo bindingInfo, LinkedBindingBuilder<T> delegate) {
      this.bindingInfo = bindingInfo;
      this.delegate = delegate;
    }

    @Override
    public ScopedBindingBuilder to(Class<? extends T> type) {
      bindingInfo.boundType = TypeLiteral.get(type);
      return delegate.to(type);
    }

    @Override
    public ScopedBindingBuilder to(TypeLiteral<? extends T> type) {
      bindingInfo.boundType = type;
      return delegate.to(type);
    }

    @Override
    public ScopedBindingBuilder to(Key<? extends T> key) {
      bindingInfo.boundType = key.getTypeLiteral();
      return delegate.to(key);
    }

    @Override
    public void toInstance(T instance) {
      // Binding to an instance, class cannot be injected
      bindingInfo.isBoundToInstance = true;
      delegate.toInstance(instance);
    }

    @Override
    public ScopedBindingBuilder toProvider(Provider<? extends T> provider) {
      // Can't do better than use our own abstract type as a guess of the bound type
      // incidentally, this works great for spy providers
      bindingInfo.boundType = bindingInfo.abstractType;
      return delegate.toProvider(provider);
    }

    @Override
    public ScopedBindingBuilder toProvider(
        Class<? extends Provider<? extends T>> providerClass) {
      bindingInfo.boundType = TypeLiteral.get(providerClass);
      return delegate.toProvider(providerClass);
    }

    @Override
    public ScopedBindingBuilder toProvider(
        Key<? extends Provider<? extends T>> key) {
      Errors errors = new Errors();
      try {
        bindingInfo.boundType = GuiceUtils.getProvidedType(key.getTypeLiteral(), errors);
      } catch (ErrorsException e) {
        errors.merge(e.getErrors()); 
      }
      errors.throwConfigurationExceptionIfErrorsExist();
      return delegate.toProvider(key);
    }

    @Override
    public void asEagerSingleton() {
      delegate.asEagerSingleton();
    }

    @Override
    public void in(Class<? extends Annotation> annotation) {
      delegate.in(annotation);
    }

    @Override
    public void in(Scope scope) {
      delegate.in(scope);
    }    
  }

  private class SpyAnnotatedBindingBuilder<T> extends SpyLinkedBindingBuilder<T>
  implements AnnotatedBindingBuilder<T> {

    private final AnnotatedBindingBuilder<T> delegate;    

    public SpyAnnotatedBindingBuilder(BindingInfo bindingInfo, AnnotatedBindingBuilder<T> delegate) {
      super(bindingInfo, delegate);
      this.delegate = delegate;
    }

    @Override
    public LinkedBindingBuilder<T> annotatedWith(
        Class<? extends Annotation> annotation) {
      try {
        bindingInfo.annotation = annotation.newInstance();
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
      return new SpyLinkedBindingBuilder<T>(bindingInfo, delegate.annotatedWith(annotation));
    }

    @Override
    public LinkedBindingBuilder<T> annotatedWith(Annotation annotation) {
      bindingInfo.annotation = annotation;
      return new SpyLinkedBindingBuilder<T>(bindingInfo, delegate.annotatedWith(annotation));
    }
  }

  private static class BindingInfo {
    private TypeLiteral<?> abstractType;
    private Annotation annotation;
    private TypeLiteral<?> boundType;
    private boolean isBoundToInstance;
  }

}