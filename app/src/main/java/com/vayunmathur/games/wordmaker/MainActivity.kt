package com.vayunmathur.games.wordmaker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.wordmaker.data.LevelDataStore
import com.vayunmathur.games.wordmaker.ui.theme.WordMakerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WordMakerTheme {
                WordMakerGameLoader()
            }
        }
    }
}

@Composable
fun WordMakerGameLoader() {
    val context = LocalContext.current
    val levelDataStore = remember { LevelDataStore(context) }
    val currentLevel by levelDataStore.currentLevel.collectAsState(initial = 1)
    var crosswordData by remember { mutableStateOf<CrosswordData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val dictionary by remember { mutableStateOf(Dictionary()) }
    val coroutineScope = rememberCoroutineScope{ Dispatchers.IO }

    LaunchedEffect(Unit) {
        coroutineScope.launch {
            dictionary.init(context)
        }
    }

    LaunchedEffect(currentLevel) { // Use currentLevel as key
        try {
            crosswordData = CrosswordData.fromAsset(context, "levels/$currentLevel.txt")
            if (crosswordData == null) {
                error = "Failed to parse level data."
            }
        } catch (e: Exception) {
            error = "Failed to load level data: ${e.message}"
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            error != null -> {
                Text(text = error!!, color = colorScheme.error)
            }
            crosswordData != null -> {
                WordGameScreen(crosswordData = crosswordData!!, levelDataStore = levelDataStore, currentLevel = currentLevel, dictionary)
            }
            else -> {
                CircularProgressIndicator()
            }
        }
    }
}

data class AnimatedLetter(
    val char: Char,
    val startOffset: Offset,
    val endOffset: Offset,
    val progress: Animatable<Float, AnimationVector1D>
)

@Composable
fun WordGameScreen(crosswordData: CrosswordData, levelDataStore: LevelDataStore, currentLevel: Int, dictionary: Dictionary) {
    val snackbarHostState = remember { SnackbarHostState() }
    var foundWords by remember(currentLevel) { mutableStateOf(setOf<String>()) }
    var bonusWords by remember(currentLevel) { mutableStateOf(setOf<String>()) }
    var formedWord by remember(currentLevel) { mutableStateOf("") }
    var showBonusWordsDialog by remember(currentLevel) { mutableStateOf(false) }
    val density = LocalDensity.current
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    var wordWithDefinition by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Animation state
    val coroutineScope = rememberCoroutineScope()
    var animatedWord by remember(currentLevel) { mutableStateOf<String?>(null) }
    val animationProgress = remember(currentLevel) { Animatable(0f) }
    var wordBoxOffset by remember(currentLevel) { mutableStateOf(Offset.Zero) }
    var bonusButtonOffset by remember(currentLevel) { mutableStateOf(Offset.Zero) }
    var crosswordCellPositions by remember(currentLevel) { mutableStateOf<Map<Pair<Int, Int>, Offset>>(emptyMap()) }
    var letterChooserPositions by remember(currentLevel) { mutableStateOf<Map<Char, Offset>>(emptyMap()) }
    var wordToAnimate by remember(currentLevel) { mutableStateOf<String?>(null) }
    var animatedLetters by remember(currentLevel) { mutableStateOf<List<AnimatedLetter>>(emptyList()) }

    LaunchedEffect(wordToAnimate) {
        wordToAnimate?.let { word ->
            val letterPositions = crosswordData.letterPositions[word]
            if (letterPositions != null) {
                val letters = word.mapIndexed { index, char ->
                    val start = (letterChooserPositions[char] ?: Offset.Zero) - rootOffset
                    val end = (crosswordCellPositions[letterPositions[index]] ?: Offset.Zero) - rootOffset

                    val offsetCorrection = with(density) { 15.dp.toPx() }
                    val correctedStart = start.plus(Offset(offsetCorrection, offsetCorrection))

                    AnimatedLetter(char, correctedStart, end, Animatable(0f))
                }
                animatedLetters = letters

                // Animate
                val jobs = letters.map {
                    launch {
                        it.progress.animateTo(1f, animationSpec = tween(durationMillis = 800))
                    }
                }
                jobs.joinAll()

                // After animation
                foundWords = foundWords + word
                wordToAnimate = null
                animatedLetters = emptyList()
            }
        }
    }

    val isWon = foundWords.containsAll(crosswordData.solutionWords)

    Scaffold(Modifier.fillMaxSize(), snackbarHost = {
        SnackbarHost(hostState = snackbarHostState)
    }) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding).padding(bottom = 64.dp)
                .fillMaxSize()
                .background(colorScheme.background)
                .onGloballyPositioned {
                    rootOffset = it.localToRoot(Offset.Zero)
                }
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Level $currentLevel",
                    style = typography.headlineMedium,
                    modifier = Modifier.padding(16.dp)
                )
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CrosswordBoard(
                        foundWords = foundWords,
                        crosswordData = crosswordData,
                        wordToAnimate = wordToAnimate,
                        onCellPositioned = { position, offset ->
                            if (crosswordCellPositions[position] != offset) {
                                crosswordCellPositions = crosswordCellPositions + (position to offset)
                            }
                        },
                        onCellClicked = { row, col ->
                            val word = crosswordData.getWordAt(row, col, foundWords)
                            if (word != null && word in foundWords) {
                                coroutineScope.launch {
                                    val definition = dictionary.getDefinition(word)
                                    if (definition != null) {
                                        wordWithDefinition = Pair(word, definition)
                                    }
                                }
                            }
                        }
                    )
                }
                Box(
                    modifier = Modifier.height(320.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isWon) {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    levelDataStore.saveLevel(currentLevel + 1)
                                }
                            }
                        ) {
                            Text("Next Level")
                        }
                    } else {
                        LetterChooser(
                            letters = crosswordData.lettersInChooser,
                            formedWord = formedWord,
                            onWordChanged = { formedWord = it },
                            onWordSubmitted = { word ->
                                if (word in crosswordData.solutionWords) {
                                    if(word !in foundWords) {
                                        wordToAnimate = word
                                    }
                                } else if (word.length < 3) {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Words must be at least 3 letters long", withDismissAction = true, duration = SnackbarDuration.Short)
                                    }
                                } else if (word.lowercase() in dictionary && word !in bonusWords) {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Bonus: '$word'", withDismissAction = true, duration = SnackbarDuration.Long)
                                    }
                                    coroutineScope.launch {
                                        animatedWord = word
                                        animationProgress.snapTo(0f)
                                        animationProgress.animateTo(
                                            1f,
                                            animationSpec = tween(durationMillis = 800)
                                        )
                                        bonusWords = bonusWords + word
                                        animatedWord = null
                                    }
                                }
                                formedWord = ""
                            },
                            onWordBoxPositioned = { wordBoxOffset = it },
                            onLetterPositioned = { char, offset ->
                                if (letterChooserPositions[char] != offset) {
                                    letterChooserPositions = letterChooserPositions + (char to offset)
                                }
                            }
                        )
                    }
                }
            }

            FilledIconButton(
                onClick = { showBonusWordsDialog = true },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
                    .padding(bottom = 32.dp)
                    .onGloballyPositioned { bonusButtonOffset = it.localToRoot(Offset.Zero) },
                enabled = bonusWords.isNotEmpty()
            ) {
                Icon(painterResource(R.drawable.outline_book_2_24), null)
            }

            if (showBonusWordsDialog) {
                BonusWordsDialog(bonusWords = bonusWords) {
                    showBonusWordsDialog = false
                }
            }

            wordWithDefinition?.let { (word, definition) ->
                DefinitionDialog(word, definition) {
                    wordWithDefinition = null
                }
            }

            animatedWord?.let { word ->
                val progress = animationProgress.value
                val currentOffset = lerp(wordBoxOffset, bonusButtonOffset, progress)
                val alpha = 1f - progress
                val scale = 1f - (progress * 0.5f)

                Text(
                    text = word,
                    color = colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp,
                    modifier = Modifier
                        .offset { IntOffset(currentOffset.x.toInt(), currentOffset.y.toInt()) }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            alpha = alpha
                        )
                )
            }
            animatedLetters.forEach { letter ->
                val progress = letter.progress.value
                val offset = lerp(letter.startOffset, letter.endOffset, progress)

                Box(
                    modifier = Modifier
                        .offset { IntOffset(offset.x.toInt(), offset.y.toInt()) }
                        .size(30.dp)
                        .padding(1.dp)
                        .background(
                            colorScheme.primaryContainer,
                            RoundedCornerShape(4.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = letter.char.toString(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
fun DefinitionDialog(word: String, definition: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = word.replaceFirstChar { it.uppercase() }) },
        text = { Text(text = definition) },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun BonusWordsDialog(bonusWords: Set<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Bonus Words") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(bonusWords.toList().sorted()) { word ->
                    Text(text = word, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss
            ) {
                Text("Close")
            }
        }
    )
}

@Composable
fun CrosswordBoard(
    foundWords: Set<String>,
    crosswordData: CrosswordData,
    wordToAnimate: String?,
    onCellPositioned: (position: Pair<Int, Int>, offset: Offset) -> Unit,
    onCellClicked: (row: Int, col: Int) -> Unit
) {
    val allCharPositions = mutableMapOf<Pair<Int, Int>, Char>()
    crosswordData.letterPositions.forEach { (word, positions) ->
        if (word in foundWords && word != wordToAnimate) {
            word.forEachIndexed { index, char ->
                allCharPositions[positions[index]] = char
            }
        }
    }

    Column {
        crosswordData.gridStructure.forEachIndexed { y, rowString ->
            Row {
                rowString.forEachIndexed { x, char ->
                    if (char != '.') {
                        val letter = allCharPositions[Pair(y, x)]
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .padding(1.dp)
                                .background(
                                    if (letter != null) colorScheme.primaryContainer else colorScheme.secondaryContainer,
                                    RoundedCornerShape(4.dp)
                                )
                                .onGloballyPositioned {
                                    onCellPositioned(Pair(y, x), it.localToRoot(Offset.Zero))
                                }
                                .clickable(enabled = letter != null) {
                                    onCellClicked(y, x)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (letter != null) {
                                Text(
                                    text = letter.toString(),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.size(30.dp))
                    }
                }
            }
        }
    }
}


@Composable
fun LetterChooser(
    letters: List<Char>,
    formedWord: String,
    onWordChanged: (String) -> Unit,
    onWordSubmitted: (String) -> Unit,
    onWordBoxPositioned: (Offset) -> Unit,
    onLetterPositioned: (char: Char, offset: Offset) -> Unit
) {
    val angleStep = 2 * Math.PI / letters.size.toDouble()
    val letterCenters = remember(letters) { mutableStateListOf(*List(letters.size, {Offset.Zero}).toTypedArray()) }
    var selectedLettersIndices by remember(letters) { mutableStateOf(listOf<Int>()) }
    var dragStartOffset by remember(letters) { mutableStateOf(Offset.Zero) }
    var currentDragPosition by remember(letters) { mutableStateOf<Offset?>(null) }

    val letterCircleRadius = with(LocalDensity.current) { 30.dp.toPx() }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = formedWord.ifEmpty { " " }, // Show a space to maintain height
            modifier = Modifier
                .padding(bottom = 20.dp)
                .graphicsLayer(alpha = if (selectedLettersIndices.isNotEmpty()) 1f else 0f)
                .background(colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .height(38.dp)
                .onGloballyPositioned { onWordBoxPositioned(it.localToRoot(Offset.Zero)) },
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = colorScheme.onPrimaryContainer
        )

        Box(
            modifier = Modifier
                .size(250.dp)
                .onGloballyPositioned {
                    dragStartOffset = it.localToRoot(Offset.Zero)
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            currentDragPosition = startOffset
                            letterCenters.forEachIndexed { idx, center ->
                                if (distance(startOffset, center) < letterCircleRadius) {
                                    if (idx !in selectedLettersIndices) {
                                        selectedLettersIndices += idx
                                        onWordChanged(selectedLettersIndices.map { letters[it] }.joinToString(""))
                                    }
                                }
                            }
                        },
                        onDrag = { change, _ ->
                            currentDragPosition = change.position
                            letterCenters.forEachIndexed { idx, center ->
                                if (distance(change.position, center) < letterCircleRadius) {
                                    if (idx !in selectedLettersIndices) {
                                        selectedLettersIndices += idx
                                        onWordChanged(selectedLettersIndices.map { letters[it] }.joinToString(""))
                                    } else if (selectedLettersIndices.size > 1 && idx == selectedLettersIndices[selectedLettersIndices.size - 2]) {
                                        selectedLettersIndices = selectedLettersIndices.dropLast(1)
                                        onWordChanged(selectedLettersIndices.map { letters[it] }.joinToString(""))
                                    }
                                }
                            }
                        },
                        onDragEnd = {
                            if (selectedLettersIndices.isNotEmpty()) {
                                onWordSubmitted(selectedLettersIndices.map { letters[it] }.joinToString(""))
                            }
                            selectedLettersIndices = emptyList()
                            currentDragPosition = null
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            val primaryColor = colorScheme.primary
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (selectedLettersIndices.size > 1) {
                    for (i in 0 until selectedLettersIndices.size - 1) {
                        val startLetter = selectedLettersIndices[i]
                        val endLetter = selectedLettersIndices[i + 1]
                        val startCenter = letterCenters[startLetter]
                        val endCenter = letterCenters[endLetter]
                        drawLine(
                            color = primaryColor,
                            start = startCenter,
                            end = endCenter,
                            strokeWidth = 10f,
                            cap = StrokeCap.Round
                        )
                    }
                }
                val lastLetter = selectedLettersIndices.lastOrNull()
                if (lastLetter != null && currentDragPosition != null) {
                    val lastCenter = letterCenters[lastLetter]
                    drawLine(
                        color = primaryColor,
                        start = lastCenter,
                        end = currentDragPosition!!,
                        strokeWidth = 10f,
                        cap = StrokeCap.Round
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize(0.9f)
                    .clip(CircleShape)
                    .background(colorScheme.secondaryContainer.copy(alpha = 0.5f))
            )

            val radius = 85.dp
            letters.forEachIndexed { index, letter ->
                val angle = angleStep * index - (Math.PI / 2)
                val x = (cos(angle) * radius.value).dp
                val y = (sin(angle) * radius.value).dp

                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(x = x, y = y)
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(if (index in selectedLettersIndices) colorScheme.primary else colorScheme.secondary)
                        .onGloballyPositioned { coordinates ->
                            val localOffset = coordinates.localToRoot(Offset.Zero) - dragStartOffset
                            val centerX = localOffset.x + letterCircleRadius
                            val centerY = localOffset.y + letterCircleRadius
                            letterCenters[index] = Offset(centerX, centerY)
                            onLetterPositioned(letter, coordinates.localToRoot(Offset.Zero))
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = letter.toString(),
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (index in selectedLettersIndices) colorScheme.onPrimary else colorScheme.onSecondary
                    )
                }
            }
        }
    }
}

private fun distance(offset1: Offset, offset2: Offset): Float {
    return sqrt((offset1.x - offset2.x).pow(2) + (offset1.y - offset2.y).pow(2))
}
