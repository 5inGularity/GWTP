/**
 * Copyright 2010 Philippe Beaudoin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.philbeaudoin.gwtp.dispatch.client.gin;

import com.google.gwt.inject.client.AbstractGinModule;
import com.philbeaudoin.gwtp.dispatch.client.ExceptionHandler;
import com.philbeaudoin.gwtp.dispatch.client.SecurityCookieAccessor;

/**
 * Abstract base class that binds an instance of {@link ExceptionHandler} for use
 * by {@link com.philbeaudoin.gwtp.dispatch.client.DispatchAsync} implementations.
 *
 * @author David Peterson
 */
public class AbstractDispatchModule extends AbstractGinModule {
  protected final Class<? extends ExceptionHandler> exceptionHandlerType;
  protected final Class<? extends SecurityCookieAccessor> sessionAccessorType;

  public AbstractDispatchModule( Class<? extends ExceptionHandler> exceptionHandlerType,
      Class<? extends SecurityCookieAccessor> sessionAccessorType ) {
    this.exceptionHandlerType = exceptionHandlerType;
    this.sessionAccessorType = sessionAccessorType;
  }

  @Override
  protected void configure() {
    bind( ExceptionHandler.class ).to( exceptionHandlerType );
    bind( SecurityCookieAccessor.class ).to( sessionAccessorType );
  }
}