package phonebook

import java.io.File
import kotlin.math.floor
import kotlin.math.sqrt

fun readFileLines(fileName: String) = File(System.getProperty("user.home") + "/Downloads/Phone_Book/" + fileName).readLines()


val toFind = readFileLines("find.txt")

fun millisToMSM(ms: Long): String {
    val msDivider = 1000
    val secondsDivider = msDivider * 60
    val millis = ms % 1000
    val seconds = (ms / msDivider) % 60
    val minutes = (ms / secondsDivider) % 60
    return "$minutes min. $seconds sec. $millis ms."
}

class SortTimeoutException : Exception()

class PhoneBookEntry(val phone: String, val name: String) {
    operator fun compareTo(another: PhoneBookEntry): Int {
        return when {
            name > another.name -> 1
            name < another.name -> -1
            else -> 0
        }
    }
}

interface Searchable {
    val entries: MutableList<PhoneBookEntry>
    var toFind: List<String>
    fun search()
}

interface Preparable {
    val entries: MutableList<PhoneBookEntry>
    fun prepare()
}

fun initPhoneBookEntries(): List<PhoneBookEntry> {
    val lines = readFileLines("directory.txt")
    return lines.map {
        val phone = it.substringBefore(" ")
        val name = it.substringAfter(" ")
        PhoneBookEntry(phone, name)
    }
}

object PhoneBook {
    var entries = initPhoneBookEntries()
            // cheat to pass bubble sort
//            .subList(0, 10000)
}

class Timer {
    private var startTs: Long = 0

    var durationMs: Long = 0

    fun start() {
        startTs = System.currentTimeMillis()
    }

    fun stop() {
        durationMs = duration()
    }

    fun duration(): Long {
        val endTs = System.currentTimeMillis()
        return endTs - startTs
    }
}

class PreparationTimeout(private val timer: Timer, private val timeout: Long) {
    fun check() {
        if (timer.duration() > timeout) {
            throw SortTimeoutException()
        }
    }
}

abstract class SearchAlgorithm(open val entries: MutableList<PhoneBookEntry>) {
    abstract fun findEntry(name: String, subEntries: MutableList<PhoneBookEntry>): Boolean

    private var preprocessor = { _: String -> entries }

    fun setPreprocessor(function: (String) -> MutableList<PhoneBookEntry>) {
        preprocessor = function
    }

    fun find(toFind: List<String>): Int {
        var foundCount = 0
        for (name in toFind) {
            if (findEntry(name, preprocessor(name))) {
                foundCount++
            }
        }
        return foundCount
    }
}

open class Searcher(
        open val name: String,
        val searchAlgorithm: (MutableList<PhoneBookEntry>) -> SearchAlgorithm,
) {
    private var preprocessor: ((String) -> MutableList<PhoneBookEntry>)? = null

    fun setPreprocessor(function: (String) -> MutableList<PhoneBookEntry>) {
        preprocessor = function
    }

    fun find(entries: MutableList<PhoneBookEntry>, toFind: List<String>): Int {
        val search = searchAlgorithm(entries)
        if (preprocessor != null) {
            search.setPreprocessor(preprocessor!!)
        }
        return search.find(toFind)
    }
}

class Preparator(
        val name: String,
        val prepareCb: (MutableList<PhoneBookEntry>, preparationTimeout: PreparationTimeout?) -> MutableList<PhoneBookEntry>,
        private val fallbackSearch: BasicSearch? = null
) {
    var fallback: Searcher? = null
    private var timeout: PreparationTimeout? = null

    fun initFallback(preparationTimer: Timer) {
        if (fallbackSearch != null) {
            timeout = PreparationTimeout(preparationTimer, fallbackSearch.searchTimer.durationMs * 10)
            fallback = fallbackSearch.searcher
        }
    }

    fun prepare(entries: MutableList<PhoneBookEntry>): MutableList<PhoneBookEntry> {
        return prepareCb(entries, timeout)
    }
}

open class BasicSearch(open var searcher: Searcher) : Searchable {
    open val name: String
        get() = searcher.name
    override var entries = PhoneBook.entries.toMutableList()
    override var toFind = emptyList<String>()
    val searchTimer = Timer()
    private var found = 0

    open fun process() {
        search()
    }

    fun start(toFind: List<String>) {
        println("Start searching ($name)...")
        this.toFind = toFind
        process()
        printResult()
        println()
    }

    override fun search() {
        searchTimer.start()
        found = search(entries)
        searchTimer.stop()
    }

    open fun search(entries: MutableList<PhoneBookEntry>): Int {
        return searcher.find(entries, toFind)
    }

    open fun duration(): Long {
        return searchTimer.durationMs
    }

    open fun printResult() {
        println("Found $found / ${toFind.size} entries. Time taken: ${millisToMSM(duration())}")
    }
}

abstract class PreparableSearch(override var searcher: Searcher) : BasicSearch(searcher), Preparable {
    abstract val prepareOperationName: String
    protected var preparationInterrupted = false
    protected val preparationTimer = Timer()

    override fun process() {
        prepare()
        super.process()
    }

    override fun duration(): Long {
        return searchTimer.durationMs + preparationTimer.durationMs
    }

    override fun printResult() {
        super.printResult()
        var sortMessage = "$prepareOperationName time: ${millisToMSM(preparationTimer.durationMs)}"
        if (preparationInterrupted) {
            sortMessage += " - STOPPED, moved to ${searcher.name}"
        }
        println(sortMessage)
        println("Searching time: ${millisToMSM(searchTimer.durationMs)}")
    }
}

class SortableSearch(override var searcher: Searcher, private val preparator: Preparator) : PreparableSearch(searcher) {
    var failedSearcher: Searcher? = null

    override val name: String
        get() = "${preparator.name} + ${(if (failedSearcher != null) failedSearcher else searcher)?.name}"

    override val prepareOperationName = "Sorting"

    override fun prepare() {
        preparationTimer.start()
        try {
            preparator.initFallback(preparationTimer)
            entries = preparator.prepare(entries)
            preparationTimer.stop()
        } catch (e: SortTimeoutException) {
            preparationTimer.stop()
            preparationInterrupted = true
            if (preparator.fallback != null) {
                searcher = preparator.fallback!!
            } else {
                throw Exception("Missing fallback on timeout")
            }
        }
    }
}

class HashSearch(override var searcher: Searcher) : PreparableSearch(searcher) {
    override val prepareOperationName = "Creating"

    private val table = mutableMapOf<Int, MutableList<PhoneBookEntry>>()

    fun hashFunction(name: String): Int {
        return name.hashCode()
    }

    override fun search(entries: MutableList<PhoneBookEntry>): Int {
        searcher.setPreprocessor { name: String ->
            val hash = hashFunction(name)
            if (table.containsKey(hash)) {
                table[hash]!!
            } else {
                mutableListOf()
            }
        }
        return super.search(entries)
    }

    override fun prepare() {
        preparationTimer.start()
        for (entry in entries) {
            val key = hashFunction(entry.name)
            if (!table.containsKey(key)) {
                table[key] = mutableListOf()
            }
            table[key]!!.add(entry)
        }
        preparationTimer.stop()
    }
}

class LinearSearch(entries: MutableList<PhoneBookEntry>) : SearchAlgorithm(entries) {
    override fun findEntry(name: String, subEntries: MutableList<PhoneBookEntry>): Boolean {
        for (entry in subEntries) {
            if (entry.name.contains(name)) {
                return true
            }
        }
        return false
    }
}

open class SearchChild(override val entries: MutableList<PhoneBookEntry>) : SearchAlgorithm(entries) {
    override fun findEntry(name: String, subEntries: MutableList<PhoneBookEntry>): Boolean {
        TODO("Implement in child")
    }
}

class JumpSearch(override val entries: MutableList<PhoneBookEntry>) : SearchChild(entries) {
    private val blockSize = floor(sqrt(entries.size.toDouble())).toInt()

    private fun makeJump(current: Int, blockSize: Int): Int {
        val next = current + blockSize
        if (next > entries.lastIndex) {
            return entries.lastIndex
        }
        return next
    }

    override fun findEntry(name: String, subEntries: MutableList<PhoneBookEntry>): Boolean {
        var current = 0
        jump@ while (true) {
            val currentEntry = subEntries[current]
            when {
                currentEntry.name == name -> {
                    return true
                }
                currentEntry.name < name -> current = makeJump(current, blockSize)
                else -> {
                    if (current == 0) {
                        break@jump
                    }
                    val blockStarts = current - blockSize + 1
                    for (backwardsI in (blockStarts until current).reversed()) {
                        val backwardsCurrent = subEntries[backwardsI]
                        if (backwardsCurrent.name == name) {
                            return true
                        }
                    }
                    break@jump
                }
            }
        }
        return false
    }
}

fun bubbleSort(entries: MutableList<PhoneBookEntry>, preparationTimeout: PreparationTimeout?): MutableList<PhoneBookEntry> {
    val preparedEntries = entries.toMutableList()
    val length = entries.size
    var iteration = 0
    while (iteration < length - 1) {
        preparationTimeout?.check()
        val last = (length - iteration - 1)
        for (i in 0 until last) {
            val current = preparedEntries[i]
            val next = preparedEntries[i + 1]
            if (current > next) {
                preparedEntries[i] = next
                preparedEntries[i + 1] = current
            }
        }
        iteration++
    }
    return preparedEntries
}

class QuickSort {
    private fun quickSortIteration(list: MutableList<PhoneBookEntry>): MutableList<PhoneBookEntry> {
        if (list.size < 2) {
            return list
        }
        val pivot = list.last()
        val greater = mutableListOf<PhoneBookEntry>()
        val smaller = mutableListOf<PhoneBookEntry>()
        for (phoneBookEntry in list) {
            if (phoneBookEntry == pivot) {
                continue
            }
            if (phoneBookEntry.name < pivot.name) {
                smaller.add(phoneBookEntry)
            } else {
                greater.add(phoneBookEntry)
            }
        }

        return (quickSortIteration(smaller) + pivot + quickSortIteration(greater)).toMutableList()
    }

    fun prepare(entries: MutableList<PhoneBookEntry>) = quickSortIteration(entries)
}

fun quickSort(entries: MutableList<PhoneBookEntry>, preparationTimeout: PreparationTimeout?) = QuickSort().prepare(entries)

class BinarySearch(override val entries: MutableList<PhoneBookEntry>) : SearchChild(entries) {
    private fun binarySearchIteration(left: Int, right: Int, value: String): Boolean {
        val middle = (left + right) / 2
        val current = entries[middle].name
        if (current == value) {
            return true
        }
        if (left == middle) {
            return false
        }
        if (current > value) {
            return binarySearchIteration(left, middle, value)
        }
        return binarySearchIteration(middle, right, value)
    }

    override fun findEntry(name: String, subEntries: MutableList<PhoneBookEntry>): Boolean {
        return binarySearchIteration(0, subEntries.lastIndex, name)
    }
}

fun searchToCallable(searchClass: (MutableList<PhoneBookEntry>) -> SearchAlgorithm): (MutableList<PhoneBookEntry>) -> SearchAlgorithm {
    return { entries: MutableList<PhoneBookEntry> ->
        searchClass(entries)
    }
}

fun main(args: Array<String>) {
    val linearSearchInstance = BasicSearch(
            Searcher("linear search", searchToCallable(::LinearSearch))
    )
    val searches = listOf(
            linearSearchInstance,
            SortableSearch(
                    Searcher("jump search", searchToCallable(::JumpSearch)),
                    Preparator("bubble sort", ::bubbleSort, linearSearchInstance),
            ),
            SortableSearch(
                    Searcher("binary search", searchToCallable(::BinarySearch)),
                    Preparator("quick sort", ::quickSort),
            ),
            HashSearch(
                    Searcher("hash table", searchToCallable(::LinearSearch))
            )
    )
    for (search in searches) {
        search.start(toFind)
    }
}
