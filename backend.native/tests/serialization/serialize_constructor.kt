class A {
    inline fun foo(x: Int) {
        val q: X = X(23)
        println("a variable initialized by inline constructor: ${q.s}")
    }
}

class X {
    @konan.internal.InlineConstructor
    constructor (x: Int) {
        println("a variable in inline constructor: $x")
    }
    val s = 19
}
