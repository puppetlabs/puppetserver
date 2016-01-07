package com.puppetlabs.puppetserver.jruby;

import org.jruby.embed.LocalContextScope;
import org.jruby.embed.LocalVariableBehavior;

/**
 * An extension of the JRuby ScriptingContainer class which is
 * slightly easier to use from Clojure
 */
public class ScriptingContainer
        extends org.jruby.embed.ScriptingContainer {
    public ScriptingContainer(LocalContextScope scope) {
        super(scope);
    }
    public ScriptingContainer(LocalContextScope scope, LocalVariableBehavior behavior) {
        super(scope, behavior);
    }

    /**
     * This method delegates to a specific signature of #callMethod from the
     * parent class.  There are many overloaded signatures in the parent class,
     * many of which have overlapping arities.  This sometimes causes problems
     * with Clojure attempting to determine the correct signature to call.
     *
     * @param receiver - the Ruby object to call a method on
     * @param methodName - the name of the method to call
     * @param args - an array of args to call the method with
     * @param returnType - the expected type of the return value from the method call
     * @return - the result of calling the method on the Ruby receiver object
     */
    public Object callMethodWithArgArray(Object receiver, String methodName,
                                         Object[] args, Class<? extends Object> returnType) {
        return callMethod(receiver, methodName, args, returnType);
    }
}
