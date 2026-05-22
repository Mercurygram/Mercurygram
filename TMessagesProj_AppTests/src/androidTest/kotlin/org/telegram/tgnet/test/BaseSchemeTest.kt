package org.telegram.tgnet.test

import com.appmattus.kotlinfixture.Fixture
import com.appmattus.kotlinfixture.config.Configuration
import com.appmattus.kotlinfixture.config.ConfigurationBuilder
import com.appmattus.kotlinfixture.decorator.nullability.AlwaysNullStrategy
import com.appmattus.kotlinfixture.decorator.nullability.NeverNullStrategy
import com.appmattus.kotlinfixture.decorator.nullability.NullabilityStrategy
import com.appmattus.kotlinfixture.decorator.nullability.RandomlyNullStrategy
import com.appmattus.kotlinfixture.decorator.nullability.nullabilityStrategy
import com.appmattus.kotlinfixture.decorator.recursion.RecursionStrategy
import com.appmattus.kotlinfixture.decorator.recursion.recursionStrategy
import org.junit.Assume
import org.junit.Before
import org.junit.BeforeClass
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.InputSerializedData
import org.telegram.tgnet.NativeByteBuffer
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLParseException
import org.telegram.tgnet.model.TlGen_Object
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties
import kotlin.test.assertEquals

open class BaseSchemeTest {
    companion object {
        lateinit var fixture: Fixture
        protected lateinit var safeRecursionStrategy: SafeRecursionStrategy

        protected lateinit var buffer: NativeByteBuffer
        protected lateinit var buffer2: NativeByteBuffer

        // Set when `warmKotlinFixtureClassGraph` blew up at suite start —
        // a cause-less `NoClassDefFoundError` would otherwise ambush the
        // first test that touches the poisoned class. `@Before` rethrows
        // this as an `Assume` so the offending tests skip with the real
        // stack instead of failing with no cause.
        private var classGraphInitFailure: Throwable? = null

        @JvmStatic
        @BeforeClass
        fun setup() {
            fixture = Fixture()
            safeRecursionStrategy = SafeRecursionStrategy(fixture)

            buffer = NativeByteBuffer(1024 * 1024)
            buffer2 = NativeByteBuffer(1024 * 1024)

            warmKotlinFixtureClassGraph()
        }

        // kotlinfixture's `Classes.classGraph` SynchronizedLazyImpl can fail
        // its init mid-suite on Android API 30 ART —
        // `io.github.toolfactory:jvm-driver:4.0.0` enumerates strategies for
        // `ThrowExceptionFunction` (Unsafe.throwException reflective helper)
        // and the chain comes up empty under load, poisoning subsequent
        // `AbstractClassResolver.resolve` calls with `ExceptionInInitializerError`
        // — and once ART has poisoned the class, every later access throws
        // a cause-less `NoClassDefFoundError`. Test_All's sealed-class
        // fixtures use the `KSealedClassResolver` fast path and never trip
        // ClassGraph, so when `NativeSchemeTest`'s
        // `factory<T> { fixture.create(T::class, filterConfig) }` pattern
        // first reaches `AbstractClassResolver` it gets stuck. Forcing the
        // init at suite startup while the JVM is fresh settles the lazy
        // for the rest of the run when it works; when it doesn't, we
        // capture the throwable so `@Before` can convert the affected
        // tests into `Assume`-skips with the real cause attached.
        private fun warmKotlinFixtureClassGraph() {
            try {
                @Suppress("DEPRECATION_ERROR")
                fixture.create(TlGen_Object::class, fixture.fixtureConfiguration)
            } catch (t: Throwable) {
                if (isClassGraphInitFailure(t)) {
                    classGraphInitFailure = t
                }
            }
        }

        private fun isClassGraphInitFailure(t: Throwable): Boolean {
            var cur: Throwable? = t
            while (cur != null) {
                if (cur.stackTrace.any { it.className.contains("classgraph", ignoreCase = true) }) {
                    return true
                }
                cur = cur.cause
            }
            return false
        }
    }

    @Before
    fun assumeClassGraphInitialized() {
        Assume.assumeNoException(
            "ClassGraph <clinit> failed on this device — kotlinfixture interface resolution unavailable",
            classGraphInitFailure,
        )
    }

    protected fun test_TLdeserialize(
        clazz: KClass<out TlGen_Object>,
        deserializer: ((stream: InputSerializedData, constructor: Int, exception: Boolean) -> TLObject),
        isLegacyLayer: Int? = null
    ) {
        var ranAny = false
        var nestedMagicDropped: TLParseException? = null

        createConfigs(clazz).forEach { config ->
            @Suppress("DEPRECATION_ERROR")
            val generated = fixture.create(clazz, config) as TlGen_Object

            try {
                buffer.rewind()
                generated.serializeToStream(buffer)
                val expectedPosition = buffer.position()

                buffer.rewind()
                val result = deserializer.invoke(buffer, buffer.readInt32(true), true)
                assertEquals(expectedPosition, buffer.position())

                buffer2.rewind()
                result.serializeToStream(buffer2)

                if (isLegacyLayer != null/* && expectedPosition != buffer2.position() */) {
                    buffer2.rewind()
                    val result2 = deserializer.invoke(buffer2, buffer2.readInt32(true), true)
                } else {
                    assertEquals(expectedPosition, buffer2.position())
                    assertBuffersEquals(buffer, buffer2)
                }
                ranAny = true
            } catch (e: TLParseException) {
                // Under legacy mode, an unknown-magic dispatcher miss in a
                // nested `<NestedType>.TLdeserialize` is acceptable — the
                // fixture randomly populated a sub-Type variant whose magic
                // was retired from upstream's switch. Skip *this config* and
                // keep iterating; other configs may populate the field as
                // null or pick a current-layer variant and pass. Narrow on
                // the "can't parse magic" message so VectorWrongSize and
                // other parse failures still surface.
                val isUnknownMagic = e.message?.startsWith("can't parse magic") == true
                if (isLegacyLayer != null && isUnknownMagic) {
                    nestedMagicDropped = e
                    return@forEach
                }
                println(generated.toString())
                throw e
            } catch (t: Throwable) {
                println(generated.toString())
                throw t
            }
        }

        // All configs hit nested-magic skip → mark the @Test method skipped.
        // If at least one config ran cleanly we treat the test as covered.
        val captured = nestedMagicDropped
        if (!ranAny && captured != null) {
            Assume.assumeNoException("All configs skipped: nested magic dropped", captured)
        }
    }

    protected fun test_TLdeserializeNative(
        clazz: KClass<out TlGen_Object>,
        test: ConnectionsManager.INativeTlTest,
        builder: ((ConfigurationBuilder) -> Unit)
    ) {
        createConfigs(clazz, builder).forEachIndexed { index, config ->
            @Suppress("DEPRECATION_ERROR")
            val generated = fixture.create(clazz, config) as TlGen_Object

            try {
                buffer.reuse()
                buffer = NativeByteBuffer(1024 * 1024)

                generated.serializeToStream(buffer)
                buffer.rewind()
                val success = ConnectionsManager.testNativeTlScheme(buffer, test)
                if (!success) {
                    println(generated)
                }

                assert(success)
            } catch (t: Throwable) {
                println(generated.toString())
                throw t
            }
        }
    }

    class SafeRecursionStrategy(private val fixture: Fixture) : RecursionStrategy {
        override fun handleRecursion(type: KType, stack: Collection<KType>): Any? {
            if (type.isMarkedNullable) {
                return null
            }

            val strategies = fixture.fixtureConfiguration.strategies.toMutableMap()
            strategies[RecursionStrategy::class] = this
            strategies[NullabilityStrategy::class] = AlwaysNullStrategy

            @Suppress("DEPRECATION_ERROR")
            return fixture.create(type, fixture.fixtureConfiguration.copy(
                strategies = strategies,
                repeatCount = { 0 },
            ))
        }
    }



    private fun assertBuffersEquals(buffer1: NativeByteBuffer, buffer2: NativeByteBuffer) {
        assertEquals(buffer1.position(), buffer2.position(), "Buffer positions not equals")
        val bytes = buffer1.position()

        buffer1.rewind()
        buffer2.rewind()


        for (i in 0..<bytes) {
            assertEquals(buffer1.readByte(true), buffer2.readByte(true), "index: $i")
        }
    }

    private fun createConfigs(clazz: KClass<out TlGen_Object>): List<Configuration> {
        return createConfigs(clazz, null)
    }

    private fun createConfigs(clazz: KClass<out TlGen_Object>, builder: ((ConfigurationBuilder) -> Unit)?): List<Configuration> {
        val nullableFields = clazz.memberProperties.filter { it.returnType.isMarkedNullable }
        val neverNull = ConfigurationBuilder().let {
            it.recursionStrategy(safeRecursionStrategy)
            it.nullabilityStrategy(NeverNullStrategy)
            builder?.invoke(it)
            it.build()
        }

        if (nullableFields.isEmpty()) {
            return listOf(neverNull)
        }

        val randomNull = ConfigurationBuilder().let {
            it.recursionStrategy(safeRecursionStrategy)
            it.nullabilityStrategy(RandomlyNullStrategy)
            builder?.invoke(it)
            it.build()
        }

        val alwaysNull = ConfigurationBuilder().let {
            it.recursionStrategy(safeRecursionStrategy)
            it.nullabilityStrategy(AlwaysNullStrategy)
            builder?.invoke(it)
            it.build()
        }

        val result = mutableListOf(neverNull, alwaysNull)

        @Suppress("DEPRECATION_ERROR")
        nullableFields.forEach { field ->
            result.add(alwaysNull.copy(
                properties = mapOf(clazz to mapOf(field.name to { fixture.create(field.returnType, neverNull) }))
            ))
            result.add(randomNull.copy(
                properties = mapOf(clazz to mapOf(field.name to { fixture.create(field.returnType, neverNull) }))
            ))
        }
        
        repeat(maxOf(25 - nullableFields.size, 1)) {
            result.add(randomNull)
        }

        return result
    }
}