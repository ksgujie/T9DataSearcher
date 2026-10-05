package com.example.t9datasearcher

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.promeg.pinyinhelper.Pinyin
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.InputStream
import java.util.UUID

data class SearchIndex(
    val initialDigits: String,
    val fullDigits: String,
    val rawValue: String
)

data class AppDatabase(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val headers: List<String> = emptyList(),
    val searchableFields: Set<String> = emptySet(),
    val records: List<Map<String, String>> = emptyList(),
    val indexMap: List<Map<String, SearchIndex>> = emptyList()
)

data class MatchResult(
    val dbName: String,
    val record: Map<String, String>,
    val matchedField: String,
    val matchedValue: String,
    val matchType: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            T9DataApp()
        }
    }
}

object AppState {
    val databases = mutableStateListOf<AppDatabase>()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun T9DataApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("t9_prefs", Context.MODE_PRIVATE) }
    
    var isT9Open by remember { mutableStateOf(prefs.getBoolean("is_t9_open", false)) }
    var selectedDbId by remember { mutableStateOf<String?>(null) }
    var exactQuery by remember { mutableStateOf("") }
    var t9Query by remember { mutableStateOf("") }
    var detailedRecord by remember { mutableStateOf<Map<String, String>?>(null) }

    fun updateT9Open(open: Boolean) {
        isT9Open = open
        prefs.edit().putBoolean("is_t9_open", open).apply()
    }

    val activeDb = AppState.databases.find { it.id == selectedDbId }
    val dbsToSearch = remember(selectedDbId, AppState.databases.toList()) {
        if (selectedDbId == null) AppState.databases.toList() else listOfNotNull(activeDb)
    }

    val searchResults = remember(t9Query, exactQuery, isT9Open, dbsToSearch) {
        if (isT9Open && t9Query.isNotEmpty()) {
            filterByT9(dbsToSearch, t9Query)
        } else if (!isT9Open && exactQuery.isNotBlank()) {
            filterByExact(dbsToSearch, exactQuery.trim())
        } else {
            emptyList()
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = if (selectedDbId == null) "T9 数据快搜" else (activeDb?.name ?: "详情"),
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    },
                    navigationIcon = {
                        if (selectedDbId != null) {
                            IconButton(onClick = { selectedDbId = null }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                )

                // 顶栏精确搜索输入框
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    OutlinedTextField(
                        value = exactQuery,
                        onValueChange = {
                            exactQuery = it
                            if (it.isNotEmpty() && isT9Open) {
                                updateT9Open(false)
                            }
                        },
                        placeholder = { Text("输入关键词精确检索...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (exactQuery.isNotEmpty()) {
                                IconButton(onClick = { exactQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "清空")
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                }
            }
        },
        floatingActionButton = {
            if (!isT9Open) {
                FloatingActionButton(
                    onClick = { updateT9Open(true) },
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Dialpad, contentDescription = "呼出 T9", tint = Color.White)
                }
            }
        },
        floatingActionButtonPosition = FabPosition.Center
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (searchResults.isNotEmpty() || (isT9Open && t9Query.isNotEmpty()) || (!isT9Open && exactQuery.isNotBlank())) {
                        SearchResultsView(
                            results = searchResults,
                            query = if (isT9Open) t9Query else exactQuery,
                            onItemClick = { detailedRecord = it }
                        )
                    } else if (selectedDbId == null) {
                        DatabaseGridScreen(
                            onSelectDb = { selectedDbId = it },
                            onImportDb = { AppState.databases.add(it) }
                        )
                    } else {
                        activeDb?.let { db ->
                            SingleDatabaseScreen(
                                database = db,
                                onUpdateDb = { updated ->
                                    val idx = AppState.databases.indexOfFirst { it.id == updated.id }
                                    if (idx != -1) AppState.databases[idx] = updated
                                },
                                onItemClick = { detailedRecord = it }
                            )
                        }
                    }
                }

                // 底部 T9 拨号键盘
                AnimatedVisibility(
                    visible = isT9Open,
                    enter = slideInVertically(initialOffsetY = { it }),
                    exit = slideOutVertically(targetOffsetY = { it })
                ) {
                    T9KeypadPanel(
                        currentInput = t9Query,
                        onKeyPress = { t9Query += it },
                        onBackspace = { if (t9Query.isNotEmpty()) t9Query = t9Query.dropLast(1) },
                        onClear = { t9Query = "" },
                        onClose = { updateT9Open(false) }
                    )
                }
            }

            // 单条记录详情弹窗
            detailedRecord?.let { record ->
                RecordDetailDialog(record = record, onDismiss = { detailedRecord = null })
            }
        }
    }
}

@Composable
fun DatabaseGridScreen(
    onSelectDb: (String) -> Unit,
    onImportDb: (AppDatabase) -> Unit
) {
    val context = LocalContext.current
    var isImporting by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            isImporting = true
            Thread {
                val db = parseExcelUri(context.contentResolver.openInputStream(it), "表单")
                if (db != null) onImportDb(db)
                isImporting = false
            }.start()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("所有数据库 (${AppState.databases.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Button(
                onClick = { filePicker.launch("*/*") },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("导入 Excel")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (AppState.databases.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (isImporting) "正在导入并预建索引..." else "暂无数据库，请点击右上角导入 Excel", color = Color.Gray)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(AppState.databases) { db ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(115.dp)
                            .clickable { onSelectDb(db.id) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(12.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(4.dp))
                            Text(db.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${db.records.size} 条记录", fontSize = 11.sp, color = Color.Gray)
                            Text("搜: ${db.searchableFields.joinToString(",")}", fontSize = 10.sp, color = Color.DarkGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SingleDatabaseScreen(
    database: AppDatabase,
    onUpdateDb: (AppDatabase) -> Unit,
    onItemClick: (Map<String, String>) -> Unit
) {
    var showFieldSelector by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(database.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("记录数: ${database.records.size} | 检索字段: ${database.searchableFields.size} 个", fontSize = 12.sp, color = Color.Gray)
            }
            OutlinedButton(
                onClick = { showFieldSelector = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("配置字段")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(database.records) { record ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onItemClick(record) },
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        record.entries.take(3).forEach { (k, v) ->
                            Text("$k: $v", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    if (showFieldSelector) {
        AlertDialog(
            onDismissRequest = { showFieldSelector = false },
            title = { Text("勾选参与检索的字段") },
            text = {
                LazyColumn {
                    items(database.headers) { header ->
                        val isChecked = database.searchableFields.contains(header)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val newSet = database.searchableFields.toMutableSet()
                                    if (isChecked) newSet.remove(header) else newSet.add(header)
                                    onUpdateDb(database.copy(searchableFields = newSet))
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            Checkbox(checked = isChecked, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text(header)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFieldSelector = false }) { Text("完成") }
            }
        )
    }
}

@Composable
fun SearchResultsView(
    results: List<MatchResult>,
    query: String,
    onItemClick: (Map<String, String>) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text("检索结果: 找到 ${results.size} 条", fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onItemClick(item.record) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${item.matchedField}: ${item.matchedValue}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(item.dbName, fontSize = 11.sp, color = Color.Gray)
                        }
                        Text("命中方式: ${item.matchType}", fontSize = 11.sp, color = Color.DarkGray)
                        Spacer(Modifier.height(4.dp))
                        val extra = item.record.entries
                            .filter { it.key != item.matchedField }
                            .take(2)
                            .joinToString(" | ") { "${it.key}: ${it.value}" }
                        if (extra.isNotEmpty()) {
                            Text(extra, fontSize = 12.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun T9KeypadPanel(
    currentInput: String,
    onKeyPress: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit
) {
    val keys = listOf(
        "1" to "", "2" to "ABC", "3" to "DEF",
        "4" to "GHI", "5" to "JKL", "6" to "MNO",
        "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
        "*" to "清空", "0" to "+", "#" to "退格"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        // T9 状态与输入回显栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("T9 键入", fontSize = 11.sp, color = Color.Gray)
                Text(
                    text = if (currentInput.isEmpty()) "点击键盘即搜..." else currentInput,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row {
                if (currentInput.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.Clear, contentDescription = "清空")
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "收起键盘")
                }
            }
        }

        // 3x4 键盘九宫格
        for (r in 0 until 4) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (c in 0 until 3) {
                    val (num, text) = keys[r * 3 + c]
                    Button(
                        onClick = {
                            when (num) {
                                "#" -> onBackspace()
                                "*" -> onClear()
                                else -> onKeyPress(num)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp)
                            .padding(vertical = 3.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(num, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            if (text.isNotEmpty()) {
                                Text(text, fontSize = 9.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RecordDetailDialog(record: Map<String, String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记录完整详情", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(record.entries.toList()) { (k, v) ->
                    Column {
                        Text(k, fontSize = 11.sp, color = Color.Gray)
                        Text(v.ifEmpty { "（空）" }, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Divider(modifier = Modifier.padding(top = 4.dp), thickness = 0.5.dp, color = Color.LightGray)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

// 核心检索算法：T9 多字段同时匹配
fun filterByT9(dbs: List<AppDatabase>, digits: String): List<MatchResult> {
    val results = mutableListOf<MatchResult>()
    for (db in dbs) {
        val targets = if (db.searchableFields.isNotEmpty()) db.searchableFields else db.headers.toSet()
        for (i in db.records.indices) {
            val record = db.records[i]
            val indexRow = db.indexMap.getOrNull(i) ?: emptyMap()
            for (f in targets) {
                val index = indexRow[f]
                if (index != null) {
                    if (index.rawValue.contains(digits)) {
                        results.add(MatchResult(db.name, record, f, index.rawValue, "数字直配"))
                        break
                    }
                    if (index.initialDigits.contains(digits)) {
                        results.add(MatchResult(db.name, record, f, index.rawValue, "首字母 (T9)"))
                        break
                    }
                    if (index.fullDigits.contains(digits)) {
                        results.add(MatchResult(db.name, record, f, index.rawValue, "全拼 (T9)"))
                        break
                    }
                }
            }
        }
    }
    return results
}

// 核心检索算法：精确关键词匹配
fun filterByExact(dbs: List<AppDatabase>, keyword: String): List<MatchResult> {
    val results = mutableListOf<MatchResult>()
    for (db in dbs) {
        val targets = if (db.searchableFields.isNotEmpty()) db.searchableFields else db.headers.toSet()
        for (record in db.records) {
            for (f in targets) {
                val value = record[f] ?: ""
                if (value.contains(keyword, ignoreCase = true)) {
                    results.add(MatchResult(db.name, record, f, value, "精确包含"))
                    break
                }
            }
        }
    }
    return results
}

fun parseExcelUri(stream: InputStream?, defaultName: String): AppDatabase? {
    if (stream == null) return null
    return try {
        val workbook = WorkbookFactory.create(stream)
        val sheet = workbook.getSheetAt(0)
        val headers = mutableListOf<String>()
        val records = mutableListOf<Map<String, String>>()
        val indexMap = mutableListOf<Map<String, SearchIndex>>()

        val headerRow = sheet.getRow(0) ?: return null
        for (cell in headerRow) {
            headers.add(cell.toString().trim())
        }

        for (r in 1..sheet.lastRowNum) {
            val row = sheet.getRow(r) ?: continue
            val rowMap = mutableMapOf<String, String>()
            val indexRow = mutableMapOf<String, SearchIndex>()
            for (c in 0 until headers.size) {
                val h = headers[c]
                val v = row.getCell(c)?.toString()?.trim() ?: ""
                rowMap[h] = v
                if (v.isNotEmpty()) {
                    indexRow[h] = buildSearchIndex(v)
                }
            }
            records.add(rowMap)
            indexMap.add(indexRow)
        }
        workbook.close()
        AppDatabase(
            name = defaultName + "_" + System.currentTimeMillis() % 1000,
            headers = headers,
            searchableFields = headers.toSet(),
            records = records,
            indexMap = indexMap
        )
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun buildSearchIndex(text: String): SearchIndex {
    val pinyinArray = text.map { char ->
        if (Pinyin.isChinese(char)) Pinyin.toPinyin(char) else char.toString()
    }
    val initials = pinyinArray.joinToString("") { it.firstOrNull()?.toString() ?: "" }
    val initialDigits = textToT9Digits(initials)
    val fullDigits = textToT9Digits(pinyinArray.joinToString(""))
    return SearchIndex(initialDigits = initialDigits, fullDigits = fullDigits, rawValue = text)
}

fun textToT9Digits(str: String): String {
    val sb = StringBuilder()
    for (ch in str.uppercase()) {
        val d = when (ch) {
            'A', 'B', 'C' -> '2'
            'D', 'E', 'F' -> '3'
            'G', 'H', 'I' -> '4'
            'J', 'K', 'L' -> '5'
            'M', 'N', 'O' -> '6'
            'P', 'Q', 'R', 'S' -> '7'
            'T', 'U', 'V' -> '8'
            'W', 'X', 'Y', 'Z' -> '9'
            in '0'..'9' -> ch
            else -> ' '
        }
        sb.append(d)
    }
    return sb.toString()
}
