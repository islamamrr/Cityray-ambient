package dev.carapps.car

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Small reflection helpers. The ECARX classes live in the head unit's system image, not
 * in our APK, so everything is looked up at runtime. On a phone these lookups fail and
 * the callers report "not available" instead of crashing.
 */
internal object Reflect {

    fun classOrNull(name: String): Class<*>? =
        try { Class.forName(name) } catch (_: Throwable) { null }

    fun call(target: Any, name: String, vararg args: Any?): Any? {
        val m = findMethod(target.javaClass, name, args.size)
            ?: throw NoSuchMethodException("${target.javaClass.name}.$name/${args.size}")
        m.isAccessible = true
        return m.invoke(target, *args)
    }

    /** Finds a public method by name and argument count, searching interfaces too. */
    fun findMethod(cls: Class<*>, name: String, argCount: Int): Method? =
        cls.methods.firstOrNull { it.name == name && it.parameterTypes.size == argCount }

    /** Finds a method whose parameter types match exactly. */
    fun findMethod(cls: Class<*>, name: String, vararg types: Class<*>): Method? =
        cls.methods.firstOrNull { it.name == name && it.parameterTypes.contentEquals(types) }

    /**
     * Implements a system interface with a lambda. Object methods (equals, hashCode,
     * toString) get sane answers, because the system may keep listeners in a map.
     */
    fun proxy(iface: Class<*>, handler: (method: String, args: Array<out Any?>) -> Any?): Any {
        val h = InvocationHandler { self, method, args ->
            val a: Array<out Any?> = args ?: emptyArray()
            when (method.name) {
                "equals" -> self === a.getOrNull(0)
                "hashCode" -> System.identityHashCode(self)
                "toString" -> "CarAppsProxy(${iface.simpleName})"
                else -> handler(method.name, a) ?: defaultFor(method.returnType)
            }
        }
        return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), h)
    }

    private fun defaultFor(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }
}
