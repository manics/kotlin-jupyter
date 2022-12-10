package org.jetbrains.kotlinx.jupyter.test.repl

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.types.shouldBeInstanceOf
import org.intellij.lang.annotations.Language
import org.jetbrains.kotlinx.jupyter.api.MimeTypedResult
import org.jetbrains.kotlinx.jupyter.api.libraries.LibraryResolutionRequest
import org.jetbrains.kotlinx.jupyter.exceptions.ReplCompilerException
import org.jetbrains.kotlinx.jupyter.libraries.AbstractLibraryResolutionInfo
import org.jetbrains.kotlinx.jupyter.libraries.KERNEL_LIBRARIES
import org.jetbrains.kotlinx.jupyter.repl.EvalResult
import org.jetbrains.kotlinx.jupyter.test.TestDisplayHandler
import org.jetbrains.kotlinx.jupyter.test.assertUnit
import org.jetbrains.kotlinx.jupyter.test.testDataDir
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.net.URLClassLoader
import kotlin.concurrent.thread
import kotlin.test.assertEquals

@Execution(ExecutionMode.SAME_THREAD)
class ReplWithStandardResolverTests : AbstractSingleReplTest() {
    private val displays = mutableListOf<Any>()
    private val handler = TestDisplayHandler(displays)
    override val repl = makeReplWithStandardResolver(handler)

    @Test
    fun testResolverRepoOrder() {
        val res = eval(
            """
            @file:Repository("https://repo.osgeo.org/repository/release/")
            @file:DependsOn("org.geotools:gt-shapefile:[23,)")
            @file:DependsOn("org.geotools:gt-cql:[23,)")
            
            %use lets-plot@f2bb7075b316e7181ff8fddb1e045c4ed2c26442(api=2.0.1)
            
            @file:DependsOn("org.jetbrains.lets-plot:lets-plot-kotlin-geotools:2.0.1")
            
            import jetbrains.letsPlot.toolkit.geotools.toSpatialDataset
            """.trimIndent(),
        )

        Assertions.assertTrue(res.metadata.newClasspath.size >= 2)
    }

    @Test
    fun testStandardLibraryResolver() {
        val baseClassLoader = repl.currentClassLoader.parent
        fun urlClassLoadersCount() = generateSequence(repl.currentClassLoader) { classLoader ->
            classLoader.parent?.takeIf { it != baseClassLoader }
        }.filter { it is URLClassLoader }.count()
        urlClassLoadersCount() shouldBe 1

        val res = eval(
            """
            %use krangl@d91d045946f59(0.16.2)
            val df = DataFrame.readCSV("src/test/testData/resolve-with-runtime.csv")
            df.head().rows.first().let { it["name"].toString() + " " + it["surname"].toString() }
            """.trimIndent(),
        )
        assertEquals("John Smith", res.resultValue)
        urlClassLoadersCount() shouldBe 2

        eval("val x = 2 + 2")
        urlClassLoadersCount() shouldBe 2
    }

    @Test
    fun testDefaultInfoSwitcher() {
        val infoProvider = repl.resolutionInfoProvider

        val initialDefaultResolutionInfo = infoProvider.fallback
        Assertions.assertTrue(initialDefaultResolutionInfo is AbstractLibraryResolutionInfo.ByClasspath)

        eval("%useLatestDescriptors")
        Assertions.assertTrue(infoProvider.fallback is AbstractLibraryResolutionInfo.ByGitRef)

        eval("%useLatestDescriptors off")
        Assertions.assertTrue(infoProvider.fallback === initialDefaultResolutionInfo)
    }

    @Test
    fun testUseFileUrlRef() {
        val commit = "cfcf8257116ad3753b176a9f779eaaea4619dacd"
        val libsCommit = "f2bb7075b316e7181ff8fddb1e045c4ed2c26442"
        val libraryPath = "src/test/testData/test-init.json"

        val res1 = eval(
            """
            %use @file[$libraryPath](name=x, value=42)
            x
            """.trimIndent(),
        )
        assertEquals(42, res1.resultValue)

        val res2 = eval(
            """
            %use @url[https://raw.githubusercontent.com/Kotlin/kotlin-jupyter/$commit/$libraryPath](name=y, value=43)
            y
            """.trimIndent(),
        )
        assertEquals(43, res2.resultValue)

        val res3 = eval("%use lets-plot@$libsCommit")
        assertEquals(1, displays.count())
        assertUnit(res3.resultValue)
        displays.clear()

        val res4 = eval(
            """
            %use @$libraryPath(name=z, value=44)
            z
            """.trimIndent(),
        )
        assertEquals(44, res4.resultValue)
    }

    @Test
    fun testHttpRedirection() {
        val res = eval(
            """
            %use jep@url[https://github.com/hanslovsky/jepyter/releases/download/jepyter-0.1.8/jep.json]
            1
            """.trimIndent(),
        ).resultValue
        assertEquals(1, res)
    }

    @Test
    fun testLibraryRequestsRecording() {
        eval("%use default")
        val res = eval("notebook.libraryRequests").resultValue

        res.shouldBeInstanceOf<List<LibraryResolutionRequest>>()
        res.shouldHaveSize(3)

        val expectedLibs = listOf("default", "dataframe", "lets-plot-dataframe")
        for (i in res.indices) {
            res[i].reference.name shouldBe expectedLibs[i]
            res[i].definition.originalDescriptorText.shouldNotBeBlank()
        }
    }

    @Test
    fun testLocalLibrariesStorage() {
        @Language("json")
        val descriptorText = """
            {
              "init": [
                "val y = 25"
              ]
            }
        """.trimIndent()

        val libName = "test-local"
        val file = KERNEL_LIBRARIES.userLibrariesDir.resolve(KERNEL_LIBRARIES.descriptorFileName(libName))
        file.delete()

        file.parentFile.mkdirs()
        file.writeText(descriptorText)

        val result = eval(
            """
            %use $libName
            y
            """.trimIndent(),
        )

        assertEquals(25, result.resultValue)
        file.delete()
    }

    @Test
    fun `multiple integrations in one JAR with the filter enabled`() {
        fun includeLib(name: String) = eval("%use @file[src/test/testData/twoFqns/$name.json]")

        includeLib("lib1")

        eval("xxx").resultValue shouldBe 1
        shouldThrow<ReplCompilerException> { eval("yyy").resultValue }

        includeLib("lib2")
        eval("yyy").resultValue shouldBe 2
    }

    @Test
    @Disabled
    fun kotlinSpark() {
        eval(
            """
            %use @file[${testDataDir.invariantSeparatorsPath}/kotlin-spark-api.json](spark = 3.2, version=1.0.4-SNAPSHOT)
            """.trimIndent(),
        )

        eval(
            """
            data class Test(
                val longFirstName: String,
                val second: LongArray,
                val somethingSpecial: Map<Int, String>,
            )

            val ds = listOf(
                Test("aaaaaaaaa", longArrayOf(1L, 100000L, 24L), mapOf(1 to "one", 2 to "two")),
                Test("bbbbbbbbb", longArrayOf(1L, 2353245L, 24L), mapOf(1 to "one", 3 to "three")),
            ).toDS(spark)
            """.trimIndent(),
        )

        var res: EvalResult? = null
        val resultThread = thread(contextClassLoader = repl.currentClassLoader) {
            res = eval("ds")
        }
        resultThread.join()
        val resultValue = res?.resultValue
        resultValue.shouldBeInstanceOf<MimeTypedResult>()
    }

    @Test
    fun `transitive sources are resolved even they are lacking for some of the dependencies in the graph`() {
        eval(
            """
            SessionOptions.resolveSources = true
            SessionOptions.serializeScriptData = true
            """.trimIndent(),
        )

        val result = eval(
            """USE {
            dependencies {
                implementation("org.apache.hadoop:hadoop-client-runtime:3.3.2")
            }
        }
            """.trimIndent(),
        )
        with(result.metadata.newSources) {
            filter { "hadoop-client-runtime" in it }.shouldBeEmpty()
            filter { "hadoop-client-api" in it }.shouldNotBeEmpty()
        }
    }
}
