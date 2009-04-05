package org.jboss.webbeans.test.unit.definition;

import javax.el.ELContext;

import org.jboss.testharness.impl.packaging.Artifact;
import org.jboss.webbeans.mock.el.EL;
import org.jboss.webbeans.test.unit.AbstractWebBeansTest;
import org.testng.annotations.Test;

@Artifact
public class ELResolverTest extends AbstractWebBeansTest
{
   
   @Test
   public void testNonBeanDoesntResolveToNamespace()
   {
      ELContext elContext = EL.createELContext();
      assert EL.EXPRESSION_FACTORY.createValueExpression(elContext, "#{foo.bar}", Object.class).getValue(elContext) == null;
   }
   
}