// WITH_REFLECT

import kotlin.test.assertEquals

fun <T> bar(): String {
    return { t: T -> t }.toString()
}

class Baz<T, V> {
    fun <V : T> baz(v: V): String {
        return (fun(t: List<T>): V = v).toString()
    }
}

fun box(): String {
    assertEquals("(T) -> T", bar<String>())
    assertEquals("(kotlin.collections.List<T>) -> V", Baz<String, Int>().baz<String>(""))
    return "OK"
}
