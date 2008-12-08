/*
 * JBoss, Home of Professional Open Source
 * Copyright 2008, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,  
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.webbeans.event;

import static org.jboss.webbeans.event.EventManager.TransactionObservationPhase.AFTER_COMPLETION;
import static org.jboss.webbeans.event.EventManager.TransactionObservationPhase.AFTER_FAILURE;
import static org.jboss.webbeans.event.EventManager.TransactionObservationPhase.AFTER_SUCCESS;
import static org.jboss.webbeans.event.EventManager.TransactionObservationPhase.BEFORE_COMPLETION;
import static org.jboss.webbeans.event.EventManager.TransactionObservationPhase.NONE;

import java.util.ArrayList;
import java.util.List;

import javax.webbeans.AfterTransactionCompletion;
import javax.webbeans.AfterTransactionFailure;
import javax.webbeans.AfterTransactionSuccess;
import javax.webbeans.BeforeTransactionCompletion;
import javax.webbeans.DefinitionException;
import javax.webbeans.Destructor;
import javax.webbeans.Disposes;
import javax.webbeans.IfExists;
import javax.webbeans.Initializer;
import javax.webbeans.Observer;
import javax.webbeans.Observes;
import javax.webbeans.Produces;
import javax.webbeans.manager.Bean;

import org.jboss.webbeans.ManagerImpl;
import org.jboss.webbeans.event.EventManager.TransactionObservationPhase;
import org.jboss.webbeans.introspector.AnnotatedMethod;
import org.jboss.webbeans.introspector.AnnotatedParameter;
import org.jboss.webbeans.util.Reflections;

/**
 * <p>
 * Reference implementation for the Observer interface, which represents an
 * observer method. Each observer method has an event type which is the class of
 * the event object being observed, and event binding types that are annotations
 * applied to the event parameter to narrow the event notifications delivered.
 * </p>
 * 
 * @author David Allen
 * 
 */
public class ObserverImpl<T> implements Observer<T>
{
   private Bean<?> observerBean;
   private final AnnotatedMethod<Object> observerMethod;
   private TransactionObservationPhase transactionObservationPhase;
   private boolean conditional;
   private ManagerImpl manager;

   
   /**
    * Creates an Observer which describes and encapsulates an observer method
    * (8.5).
    * 
    * @param componentModel The model for the component which defines the
    *           observer method
    * @param observer The observer method to notify
    * @param eventType The type of event being observed
    * @param beanModel The model for the bean which defines the observer method
    * @param observer The observer method to notify
    * @param eventType The type of event being observed
    */
   public ObserverImpl(final AnnotatedMethod<Object> observer, final Bean<?> observerBean, final ManagerImpl manager)
   {
      this.manager = manager;
      this.observerBean = observerBean;
      this.observerMethod = observer;
      validateObserverMethod();
      initTransactionObservationPhase();
      conditional = !observerMethod.getAnnotatedParameters(IfExists.class).isEmpty();
   }

   private void initTransactionObservationPhase()
   {
      List<TransactionObservationPhase> observationPhases = new ArrayList<TransactionObservationPhase>();
      if (!observerMethod.getAnnotatedParameters(BeforeTransactionCompletion.class).isEmpty())
      {
         observationPhases.add(BEFORE_COMPLETION);
      }
      if (!observerMethod.getAnnotatedParameters(AfterTransactionCompletion.class).isEmpty())
      {
         observationPhases.add(AFTER_COMPLETION);
      }
      if (!observerMethod.getAnnotatedParameters(AfterTransactionFailure.class).isEmpty())
      {
         observationPhases.add(AFTER_FAILURE);
      }
      if (!observerMethod.getAnnotatedParameters(AfterTransactionSuccess.class).isEmpty())
      {
         observationPhases.add(AFTER_SUCCESS);
      }
      if (observationPhases.size() > 1)
      {
         throw new DefinitionException("Transactional observers can only observe on a single phase");
      }
      else if (observationPhases.size() == 1)
      {
         transactionObservationPhase = observationPhases.iterator().next();
      }
      else
      {
         transactionObservationPhase = NONE;
      }
   }

   /**
    * Performs validation of the observer method for compliance with the specifications.
    */
   private void validateObserverMethod()
   {
      // Make sure exactly one and only one parameter is annotated with Observes
      List<AnnotatedParameter<Object>> eventObjects = this.observerMethod.getAnnotatedParameters(Observes.class);
      if (eventObjects.size() > 1)
      {
         throw new DefinitionException(this + " is invalid because it contains more than event parameter");
      }
      // Make sure the event object above is not parameterized with a type
      // variable or wildcard
      if (eventObjects.size() > 0)
      {
         AnnotatedParameter<Object> eventParam = eventObjects.iterator().next();
         if (Reflections.isParameterizedType(eventParam.getType()))
         {
            throw new DefinitionException(this + " cannot observe parameterized event types");
         }
      }
      // Check for parameters annotated with @Disposes
      List<AnnotatedParameter<Object>> disposeParams = this.observerMethod.getAnnotatedParameters(Disposes.class);
      if (disposeParams.size() > 0)
      {
         throw new DefinitionException(this + " cannot have any parameters annotated with @Dispose");
      }
      // Check annotations on the method to make sure this is not a producer
      // method, initializer method, or destructor method.
      if ( this.observerMethod.isAnnotationPresent(Produces.class) )
      {
         throw new DefinitionException(this + " cannot be annotated with @Produces");
      }
      if (this.observerMethod.isAnnotationPresent(Initializer.class))
      {
         throw new DefinitionException(this + " cannot be annotated with @Initializer");
      }
      if ( this.observerMethod.isAnnotationPresent(Destructor.class) )
      {
         throw new DefinitionException(this + " cannot be annotated with @Destructor");
      }
   }

   public void notify(final T event)
   {
      // Get the most specialized instance of the component
      Object instance = getInstance(isConditional());
      if (instance != null)
      {
         // TODO replace event parameter
         observerMethod.invokeWithSpecialValue(manager, instance, Observes.class, event);
      }

   }

   /**
    * Uses the container to retrieve the most specialized instance of this
    * observer.
    * 
    * @param conditional T
    * 
    * @return the most specialized instance
    */
   protected Object getInstance(boolean conditional)
   {
      // Return the most specialized instance of the component
      return manager.getMostSpecializedInstance(observerBean, conditional);
   }

   /**
    * Indicates if the observer is transactional
    * 
    * @return True if transactional, false otherwise
    */
   public boolean isTransactional()
   {
      return !TransactionObservationPhase.NONE.equals(transactionObservationPhase);
   }

   /**
    * Indicates if the observer is conditional
    * 
    * @return True if conditional, false otherwise
    */
   public boolean isConditional()
   {
      return conditional;
   }

   /**
    * Checks if the observer is interested in a particular transactional phase
    * 
    * @param currentPhase The phase to check
    * @return True if interested, false otherwise
    */
   public boolean isInterestedInTransactionPhase(TransactionObservationPhase currentPhase)
   {
      return transactionObservationPhase.equals(currentPhase);
   }
   
   @Override
   public String toString()
   {
      StringBuilder builder = new StringBuilder();
      builder.append("Observer Implentation: \n");
      builder.append("  Observer (Declaring) bean: " + observerBean);
      builder.append("  Observer method: " + observerMethod);
      return builder.toString();
   }

}
