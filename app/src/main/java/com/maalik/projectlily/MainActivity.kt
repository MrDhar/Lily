package com.maalik.projectlily
import android.app.*
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.content.*
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.text.*
import android.text.style.ForegroundColorSpan
import android.text.Spanned
import android.view.*
import android.widget.*
import java.io.*
import java.util.LinkedHashMap
import java.util.Locale
import java.util.regex.Pattern
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import com.maalik.projectlily.rules.MySqlStrictRuleValidator
import kotlin.math.roundToInt



class MainActivity : Activity() {
    private var schemaFilter: String = ""
    private var editorZoom = 1.0f
    private var resultZoom = 0.72f
    private var commandPaletteX = -1
    private var commandPaletteY = -1
    private var editorBaseSp = 13f
    private var currentStatementIndicator: TextView? = null
    // Android OnContextClickListener supplies only the View, so remember the last
    // mouse position from hover events for accurate desktop-style context menus.
    private var lastEditorPointerX = 0f
    private var lastEditorPointerY = 0f
    internal fun applyEditorZoomFromGesture(scaleFactor:Float){
        applyEditorZoom(editorZoom*scaleFactor,false)
    }
    private fun applyEditorZoom(z: Float, notify: Boolean = false) {
        editorZoom = z.coerceIn(0.85f, 1.30f)
        editor.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, editorBaseSp * editorZoom)
    }
    private fun applyResultZoom(z: Float, notify: Boolean = false) {
        resultZoom = z.coerceIn(0.42f, 1.30f)
        resultContainer.findVirtualResultGrids().forEach { it.setZoom(resultZoom) }
    }
    private fun View.findVirtualResultGrids(): List<VirtualResultGridView> {
        val out = ArrayList<VirtualResultGridView>()
        fun walk(v: View) {
            if (v is VirtualResultGridView) out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(this); return out
    }
    private lateinit var editor: LineNumberEditText
    internal fun editorForShortcuts(): LineNumberEditText = editor
    private lateinit var scriptTabStrip: LinearLayout
    private lateinit var status: TextView
    private lateinit var tabStrip: LinearLayout
    private lateinit var resultContainer: FrameLayout
    private lateinit var resultArea: View
    private lateinit var errorPanelController: ErrorPanelController
    private lateinit var editorSurface: View
    private lateinit var resizeHandle: View
    private lateinit var db: SQLiteDatabase
    private lateinit var dbFile: File
    private var schemaPanel: LinearLayout? = null
    private var sidebarPanel: View? = null
    private var sidebarDivider: View? = null
    private var databaseSwitcherPopup: PopupWindow? = null
    private var sidebarOpen = true
    private var focusMode = false
    private var focusSidebarWasOpen = false
    private var switchingScriptTab = false
    private data class ScriptTab(var name:String, var text:String, var selection:Int=0, var dirty:Boolean=false)
    private val scriptTabs=mutableListOf<ScriptTab>()
    private var activeScriptTab=0
    @Volatile private var importCancelled = false
    @Volatile private var importInProgress = false
    @Volatile private var queryRunning = false
    @Volatile private var queryCancelled = false
    @Volatile private var activeQueryCancellation: CancellationSignal? = null
    private var importDialog: Dialog? = null
    private var importDots: LoadingDotsView? = null
    private var importCancelButton: Button? = null
    private var importTitleView: TextView? = null
    private var importDetailView: TextView? = null
    private var highlighting = false
    private lateinit var editorShortcuts: EditorShortcuts
    private lateinit var windowDotsController: WindowDotsController
    private lateinit var resultExportController: ResultExportController
    private lateinit var queryFileSaveController: QueryFileSaveController
    private var currentChallenge = -1
    private var bundledAsset = "Parks_and_Recreation.db"
    private val challengeSql = listOf(
        "SELECT * FROM employee_demographics WHERE age > 40;",
        "SELECT d.first_name, d.last_name, s.salary FROM employee_demographics d JOIN employee_salary s ON d.employee_id=s.employee_id;",
        "SELECT first_name, salary FROM employee_salary ORDER BY salary DESC LIMIT 3;",
        "SELECT gender, COUNT(*) AS total FROM employee_demographics GROUP BY gender;",
        "SELECT * FROM parks_departments ORDER BY department_id;"
    )
    private val prefs by lazy { getSharedPreferences("lily", MODE_PRIVATE) }
    private val history = mutableListOf<String>()
    private val snippets = linkedMapOf<String, String>()
    private val blue=Color.rgb(224,225,227); private val purple=Color.rgb(184,186,190)
    private val green=Color.rgb(143,175,146); private val orange=Color.rgb(190,192,196)
    private val red=Color.rgb(255,107,100); private val white=Color.rgb(231,227,233)
    private val keywords = listOf("SELECT","FROM","WHERE","INSERT","INTO","VALUES","UPDATE","SET","DELETE","CREATE","TABLE","DROP","ALTER","JOIN","INNER","LEFT","RIGHT","FULL","OUTER","CROSS","NATURAL","ON","USING","GROUP","BY","ORDER","ASC","DESC","HAVING","LIMIT","OFFSET","AS","DISTINCT","AND","OR","NOT","NULL","IS","LIKE","GLOB","REGEXP","IN","BETWEEN","ESCAPE","UNION","INTERSECT","EXCEPT","ALL","CASE","WHEN","THEN","ELSE","END","COUNT","SUM","AVG","MIN","MAX","TOTAL","GROUP_CONCAT","OVER","PARTITION","ROWS","RANGE","GROUPS","UNBOUNDED","PRECEDING","FOLLOWING","CURRENT","ROW","ROW_NUMBER","RANK","DENSE_RANK","NTILE","LAG","LEAD","FIRST_VALUE","LAST_VALUE","NTH_VALUE","FILTER","WITH","RECURSIVE","EXISTS","RETURNING","PRIMARY","KEY","FOREIGN","REFERENCES","DEFAULT","UNIQUE","CHECK","CONSTRAINT","AUTOINCREMENT","GENERATED","ALWAYS","STORED","VIRTUAL","WITHOUT","ROWID","STRICT","COLLATE","INDEX","VIEW","TRIGGER","BEFORE","AFTER","INSTEAD","OF","FOR","EACH","BEGIN","CASCADE","RESTRICT","IF","RENAME","COLUMN","TO","ADD","TEMP","TEMPORARY","TRANSACTION","COMMIT","ROLLBACK","SAVEPOINT","RELEASE","ATTACH","DETACH","EXPLAIN","QUERY","PLAN","ANALYZE","VACUUM","PRAGMA","CAST","COALESCE","IFNULL","NULLIF","TRUE","FALSE","CURRENT_DATE","CURRENT_TIME","CURRENT_TIMESTAMP")
    data class ResultTab(val button: Button, val moreButton: TextView, val closeButton: Button, val container: LinearLayout, val view: View, val isError: Boolean, val csv: List<List<String>>?, val title: String, val errorMessage: String? = null, val query: String? = null, val pinned: Boolean = false)
    data class QueryError(val statementNumber: Int, val message: String, val location: String, val elapsedMs: String, val query: String)
    private val tabs=mutableListOf<ResultTab>(); private var activeTab=-1; private var pendingCsv:String?=null; private var nextResultTabNumber=1
    // Keep storage, schema discovery and file-provider work off the UI thread.
    private val backgroundExecutor=Executors.newFixedThreadPool(3)
    private val databaseSummaryCache=ConcurrentHashMap<String,String>()
    @Volatile private var schemaRefreshGeneration=0
    private val mainHandler=Handler(Looper.getMainLooper())
    private var highlightRunnable:Runnable?=null
    override fun onCreate(b: Bundle?) { super.onCreate(b); setContentView(R.layout.activity_main); enterImmersiveMode()
        editor=findViewById(R.id.editor); editorShortcuts=EditorShortcuts(this); findViewById<LineNumberGutterView>(R.id.editorGutter).bindEditor(editor); scriptTabStrip=findViewById(R.id.scriptTabStrip); editorBaseSp=13f; currentStatementIndicator=findViewById(R.id.currentStatementIndicator); setupScriptTabs(); editor.setOnKeyListener{_,keyCode,event->
            if(event.action!=KeyEvent.ACTION_DOWN)return@setOnKeyListener false
            val mod=event.isCtrlPressed||event.isMetaPressed
            if(!mod)return@setOnKeyListener false
            editorShortcuts.handle(keyCode,event)
        }; status=findViewById(R.id.status); tabStrip=findViewById(R.id.tabStrip); resultContainer=findViewById(R.id.resultContainer); resultArea=findViewById(R.id.resultArea)
        findViewById<TextView>(R.id.editorZoomMinus).setOnClickListener{applyEditorZoom(editorZoom-0.05f,true)}
        findViewById<TextView>(R.id.editorZoomPlus).setOnClickListener{applyEditorZoom(editorZoom+0.05f,true)}
        findViewById<TextView>(R.id.resultZoomMinus).setOnClickListener{applyResultZoom(resultZoom-0.08f,true)}
        findViewById<TextView>(R.id.resultZoomPlus).setOnClickListener{applyResultZoom(resultZoom+0.08f,true)}
        resultExportController=ResultExportController(this){ index -> tabs.getOrNull(index)?.takeIf{!it.isError}?.csv }; queryFileSaveController=QueryFileSaveController(this){ if(scriptTabs.isEmpty()) "Query 1" to editor.text.toString() else scriptTabs[activeScriptTab].name to editor.text.toString() }; errorPanelController=ErrorPanelController(this, findViewById(R.id.errorPanel)); editorSurface=findViewById(R.id.editorSurface); resizeHandle=findViewById(R.id.resizeHandle); schemaPanel=findViewById(R.id.schemaPanel); sidebarPanel=findViewById(R.id.sidebarPanel); sidebarDivider=findViewById(R.id.sidebarDivider)
        loadPrefs(); openSavedOrBundledDatabase()
        val defaultSql="""-- Select a query and tap Run.
-- Select multiple statements to run only that selection.
SELECT * FROM employee_demographics;
SELECT first_name, salary
FROM employee_salary
ORDER BY salary DESC;"""
        restoreScriptTabs(defaultSql)
        editor.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,st:Int,c:Int,a:Int){};override fun onTextChanged(s:CharSequence?,st:Int,b:Int,c:Int){if(!switchingScriptTab&&scriptTabs.isNotEmpty()){scriptTabs[activeScriptTab].text=s?.toString().orEmpty();scriptTabs[activeScriptTab].selection=editor.selectionStart.coerceAtLeast(0);scriptTabs[activeScriptTab].dirty=true;renderScriptTabs()}};override fun afterTextChanged(e:Editable?){scheduleHighlight();editor.invalidate()}})
        editor.setSelection(editor.length())
        editor.post { editor.requestFocus(); editor.setSelection(editor.selectionStart.coerceIn(0,editor.length())) }
        findViewById<ImageButton>(R.id.runButton).setOnClickListener{runSelectedOrAll()}; findViewById<ImageButton>(R.id.runCurrentButton).setOnClickListener{runSelectedOrAll(true)}; findViewById<Button>(R.id.importButton).setOnClickListener{pickFile()}; if(android.os.Build.VERSION.SDK_INT>=23){editor.setOnHoverListener{_,e->if(e.actionMasked==MotionEvent.ACTION_HOVER_MOVE||e.actionMasked==MotionEvent.ACTION_HOVER_ENTER){lastEditorPointerX=e.x;lastEditorPointerY=e.y};false};}; installMacPointerIcons()
        findViewById<Button>(R.id.formatButton).setOnClickListener{editor.setText(formatSql(editor.text.toString())); editor.setSelection(editor.length())}
        findViewById<ImageButton>(R.id.clearButton).setOnClickListener{clearEditor()};
        setupResizeHandle(); setupErrorPanel(); setupWindowDots(); refreshSchemaPanel(); showPlaceholder()
    }
    private fun enterImmersiveMode(){
        if(android.os.Build.VERSION.SDK_INT>=30){
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let{ controller ->
                controller.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior=android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }else{
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility=(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }
    override fun onWindowFocusChanged(hasFocus:Boolean){
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus) enterImmersiveMode()
    }
    private fun loadPrefs(){
        history.addAll(prefs.getStringSet("history", emptySet<String>())!!.toList())
        prefs.all.filterKeys{it.startsWith("snippet:")}.forEach{entry->(entry.value as? String)?.let{snippets[entry.key.removePrefix("snippet:")]=it}}
    }
    private fun savePrefs(){prefs.edit().putStringSet("history",history.take(30).toSet()).apply()}
    private fun userDatabasePaths():MutableList<String>{
        return prefs.getStringSet("user_db_paths", emptySet<String>())?.filter{it.isNotBlank()&&File(it).exists()}?.toMutableList() ?: mutableListOf()
    }
    private fun saveUserDatabasePaths(paths:Collection<String>){prefs.edit().putStringSet("user_db_paths",paths.toSet()).apply()}
    private fun databaseLabel(path:String):String{
        val stored=prefs.getString("db_label_"+path.hashCode(),null)
        if(!stored.isNullOrBlank())return stored
        var n=File(path).name
        n=n.replace(Regex("^Imported_\\d+_"),"")
        return n.substringBeforeLast('.',n).ifBlank{"Database"}
    }
    private fun registerUserDatabase(path:String,label:String){
        val paths=userDatabasePaths();paths.remove(path);paths.add(path);saveUserDatabasePaths(paths)
        prefs.edit().putString("db_label_"+path.hashCode(),label.ifBlank{databaseLabel(path)}).apply()
    }
    private fun unregisterUserDatabase(path:String){
        val paths=userDatabasePaths();paths.remove(path);saveUserDatabasePaths(paths);prefs.edit().remove("db_label_"+path.hashCode()).apply()
    }
    private fun isBundledSample(path:String):Boolean=path.startsWith(filesDir.absolutePath+File.separator+"Sample_")
    private fun configureDatabase(database:SQLiteDatabase){
        // Large-data profile: keep SQLite disk-backed and avoid turning the Android
        // heap into a cache for the database. All pragmas are best-effort because
        // imported databases may come from older SQLite builds.
        try{database.rawQuery("PRAGMA journal_mode=WAL",null).use{if(it.moveToFirst())it.getString(0)}}catch(_:Exception){}
        try{database.execSQL("PRAGMA synchronous=NORMAL")}catch(_:Exception){}
        try{database.execSQL("PRAGMA foreign_keys=ON")}catch(_:Exception){}
        try{database.execSQL("PRAGMA busy_timeout=5000")}catch(_:Exception){}
        try{database.execSQL("PRAGMA temp_store=FILE")}catch(_:Exception){}
        // Negative cache_size means KiB, so this caps SQLite's page cache around 8 MiB.
        // It prevents large databases from consuming the app heap simply by being opened.
        try{database.execSQL("PRAGMA cache_size=-8192")}catch(_:Exception){}
        try{database.execSQL("PRAGMA wal_autocheckpoint=1000")}catch(_:Exception){}
    }
    private fun openSavedOrBundledDatabase(){
        val saved=prefs.getString("active_db_path",null)
        if(!saved.isNullOrBlank()){
            val f=File(saved)
            if(f.exists()){try{dbFile=f;db=SQLiteDatabase.openDatabase(f.path,null,SQLiteDatabase.OPEN_READWRITE);configureDatabase(db);if(!isBundledSample(f.path))registerUserDatabase(f.path,databaseLabel(f.path));return}catch(_:Exception){}}
        }
        val users=userDatabasePaths()
        if(users.isNotEmpty()){
            val f=File(users.first())
            try{dbFile=f;db=SQLiteDatabase.openDatabase(f.path,null,SQLiteDatabase.OPEN_READWRITE);configureDatabase(db);prefs.edit().putString("active_db_path",f.path).apply();return}catch(_:Exception){}
        }
        copyBundledDatabase("Parks_and_Recreation.db")
        prefs.edit().putString("active_db_path",dbFile.path).apply()
    }
    private fun copyBundledDatabase(asset:String){
        val safe=asset.replace(Regex("[^A-Za-z0-9._-]"),"_")
        dbFile=File(filesDir,"Sample_$safe")
        if(!dbFile.exists()) assets.open(asset).use{i->dbFile.outputStream().use{o->i.copyTo(o)}}
        db=SQLiteDatabase.openOrCreateDatabase(dbFile,null);configureDatabase(db)
    }
    private fun activateDatabase(path:String,label:String?=null){
        if(queryRunning){showError("Stop the running query before switching databases");return}
        val target=File(path)
        val opened=SQLiteDatabase.openDatabase(target.path,null,SQLiteDatabase.OPEN_READWRITE);configureDatabase(opened)
        if(::db.isInitialized&&db.isOpen)db.close()
        db=opened;dbFile=target
        prefs.edit().putString("active_db_path",target.path).apply()
        label?.let{prefs.edit().putString("db_label_"+target.path.hashCode(),it).apply()}
        clearResults();showPlaceholder();editor.setText(if(firstTable().isNotBlank())"SELECT * FROM \"${firstTable().replace("\"","\"\"")}\";" else "");editor.setSelection(editor.length());refreshSchemaPanel();statusOk("Using ${databaseLabel(target.path)}")
    }
    private fun pickFile(){
        if(queryRunning){showError("Stop the running query before changing databases or importing data");return}
        // Use the real Android document picker as the file browser. Keep the MIME
        // filter completely open so SQL dumps, vendor exports, .bak/.dump files,
        // SQLite files and text files are not hidden by provider-specific filters.
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{
            addCategory(Intent.CATEGORY_OPENABLE)
            type="*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent,77)
    }
    private fun startImport(uri:Uri,fileName:String){
        if(importInProgress)return
        val name=fileName.lowercase(Locale.US)
        val title=when{
            name.endsWith(".db")||name.endsWith(".sqlite")||name.endsWith(".sqlite3")->"Importing database"
            name.endsWith(".csv")||name.endsWith(".tsv")->"Importing data"
            name.endsWith(".json")||name.endsWith(".ndjson")||name.endsWith(".jsonl")->"Importing JSON"
            else->"Importing SQL"
        }
        showImportProgress(title,fileName)
        importCancelled=false
        importInProgress=true
        Thread {
            try{
                checkImportCancelled()
                val outcome=when{
                    name.endsWith(".db")||name.endsWith(".sqlite")||name.endsWith(".sqlite3")->contentResolver.openInputStream(uri)?.use{importDatabaseStream(it,fileName)} ?: throw Exception("Could not read the selected file")
                    name.endsWith(".sql")||name.endsWith(".mysql")||name.endsWith(".txt")||name.endsWith(".dump")||name.endsWith(".dmp")->importSqlStream(uri,fileName)
                    name.endsWith(".csv")||name.endsWith(".tsv")->importDelimitedStream(uri,fileName,if(name.endsWith(".tsv"))'\t' else ',')
                    name.endsWith(".json")||name.endsWith(".ndjson")||name.endsWith(".jsonl")->importJsonStream(uri,fileName)
                    else->{
                        throw Exception("Unsupported file. Choose SQL text (.sql/.mysql/.dump/.dmp), SQLite (.db/.sqlite/.sqlite3), CSV/TSV, or JSON/NDJSON/JSONL.")
                    }
                }
                checkImportCancelled()
                runOnUiThread{if(!isFinishing && !isDestroyed)finishImportedDatabase(outcome) else {try{outcome.database.close()}catch(_:Exception){};outcome.target.delete();importInProgress=false}}
            }catch(_:ImportCancelledException){
                runOnUiThread{if(!isFinishing && !isDestroyed)showImportCancelled() else {importInProgress=false}}
            }catch(e:OutOfMemoryError){
                runOnUiThread{if(!isFinishing && !isDestroyed)showImportFailure("Import ran out of memory. Large imports use streaming mode; this file may contain a format that requires too much in-memory parsing.",uri,fileName) else {importInProgress=false}}
            }catch(e:Exception){
                runOnUiThread{if(!isFinishing && !isDestroyed)showImportFailure(e.message?:"Could not read/import the selected file",uri,fileName) else {importInProgress=false}}
            }
        }.start()
    }
    private data class ParsedInsert(val table:String,val columns:List<String>,val values:List<String>)
    private fun hasUserTables(database:SQLiteDatabase):Boolean = try {
        database.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata' LIMIT 1",null).use{it.moveToFirst()}
    } catch(_:Exception){ false }
    private fun databaseSummary(database:SQLiteDatabase):String = try {
        val tables=database.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata'",null).use{if(it.moveToFirst())it.getInt(0)else 0}
        val views=database.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='view' AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata'",null).use{if(it.moveToFirst())it.getInt(0)else 0}
        buildString { append(tables).append(if(tables==1)" table" else " tables"); if(views>0)append(" · ").append(views).append(if(views==1)" view" else " views") }
    } catch(_:Exception){ "SQLite database" }
    private fun inferImportType(values:List<String>):String = inferTabularType(values)
    private fun stripLeadingSqlComments(sql:String):String{
        var s=sql.removePrefix("\uFEFF").trimStart()
        while(true){
            s=when{
                s.startsWith("/*")-> { val end=s.indexOf("*/",2); if(end<0) return s else s.substring(end+2).trimStart() }
                s.startsWith("--")-> { val end=s.indexOf('\n'); if(end<0) return "" else s.substring(end+1).trimStart() }
                s.startsWith("#")-> { val end=s.indexOf('\n'); if(end<0) return "" else s.substring(end+1).trimStart() }
                else->return s
            }
        }
    }
    private fun shouldSkipImportStatement(sql:String):Boolean {
        val s=stripLeadingSqlComments(sql).trim().removeSuffix(";").trimStart()
        if(s.isBlank())return true
        val u=s.uppercase(Locale.US)
        return when {
            u.startsWith("SET ") ||
            u.startsWith("LOCK TABLES") || u.startsWith("UNLOCK TABLES") ||
            u.startsWith("ALTER TABLE") && u.contains("DISABLE KEYS") || u.startsWith("ALTER TABLE") && u.contains("ENABLE KEYS") ||
            u.startsWith("SET ANSI_") || u.startsWith("SET QUOTED_IDENTIFIER") || u.startsWith("SET ANSI_NULLS") ||
            u.startsWith("SET NOCOUNT") || u.startsWith("SET XACT_ABORT") || u.startsWith("SET CONCAT_NULL_YIELDS_NULL") ||
            u.startsWith("SET IDENTITY_INSERT") -> true
            u.startsWith("USE ") || u.startsWith("GO") || u.startsWith("DELIMITER ") -> true
            u.startsWith("CREATE DATABASE") || u.startsWith("DROP DATABASE") ||
            u.startsWith("CREATE SCHEMA") || u.startsWith("DROP SCHEMA") -> true
            u.startsWith("CREATE INDEX") || u.startsWith("CREATE UNIQUE INDEX") || u.startsWith("CREATE CLUSTERED INDEX") ||
            u.startsWith("CREATE NONCLUSTERED INDEX") || u.startsWith("DROP INDEX") -> true
            u.startsWith("CREATE TRIGGER") || u.startsWith("DROP TRIGGER") || u.startsWith("ALTER TABLE") && (u.contains(" ADD CONSTRAINT") || u.contains(" DROP CONSTRAINT")) -> true
            u.startsWith("IF EXISTS") || u.startsWith("IF NOT EXISTS") && !u.contains("CREATE TABLE") && !u.contains("CREATE VIEW") -> true
            u.startsWith("START TRANSACTION") || u.startsWith("BEGIN TRANSACTION") || u=="BEGIN" || u=="COMMIT" || u=="ROLLBACK" ||
            u.startsWith("SAVEPOINT ") || u.startsWith("RELEASE SAVEPOINT") || u.startsWith("SET AUTOCOMMIT") -> true
            u.startsWith("EXEC ") || u.startsWith("EXECUTE ") -> true
            u.startsWith("CREATE SEQUENCE") || u.startsWith("ALTER SEQUENCE") || u.startsWith("DROP SEQUENCE") || u.startsWith("SELECT PG_CATALOG.SETVAL") || u.startsWith("SELECT SETVAL") -> true
            u.startsWith("CREATE EXTENSION") || u.startsWith("COMMENT ON ") || u.startsWith("ALTER EXTENSION") -> true
            u.startsWith("ALTER TABLE") && (u.contains(" OWNER TO ") || u.contains(" ENABLE ROW LEVEL SECURITY") || u.contains(" DISABLE ROW LEVEL SECURITY")) -> true
            u.startsWith("CREATE RULE") || u.startsWith("CREATE POLICY") -> true
            u.startsWith("PRINT ") -> true
            u.startsWith("GRANT ") || u.startsWith("REVOKE ") -> true
            u.contains("SYS.SYSREFERENCES") || u.contains("SYS.SYSOBJECTS") || u.contains("SYS.SYSINDEXES") -> true
            u.startsWith("ALTER TABLE") && u.contains("FOREIGN KEY") -> true
            else -> false
        }
    }
    private fun prepareSqlForSQLite(raw:String):Pair<String,Int>{
        var s=raw.removePrefix("\uFEFF").trim()
        if(s.isBlank())return "" to 0
        // Keep very large INSERT payloads out of the general multi-regex normalization
        // path. MySQL dump INSERTs are already SQLite-compatible with backtick quoting,
        // and are later split into bounded row batches.
        if(isInsertStatement(s)&&s.length>=LARGE_IMPORT_STATEMENT_BYTES)return s to 0
        s=s.replace("\u0000","").replace("\u001A","").replace("\u200B","").replace("\u200C","").replace("\u200D","").replace("\uFEFF","").replace('\u00A0',' ').trim()
        // Some exported MySQL dumps escape identifier backticks as \`name\`. SQLite expects
        // the backticks themselves, not the escape character. Only remove this form outside
        // single-quoted string literals so data values remain untouched.
        s=normalizeEscapedMySqlBackticks(s)
        if(shouldSkipImportStatement(s)) return "" to 1
        // Normalize common vendor INSERT spellings before classification.
        s=replaceOutsideSingleQuotes(s,Regex("""(?i)^\s*INSERT\s+(?!(?:INTO\b|OR\s+(?:IGNORE|REPLACE)\s+INTO\b))(?=(?:\[[^\]]+\]\.)?\[[^\]]+\]|(?:[A-Za-z_][A-Za-z0-9$]*\.)?[A-Za-z_][A-Za-z0-9$]*)""")){"INSERT INTO "}
        s=replaceOutsideSingleQuotes(s,Regex("""(?i)^\s*INSERT\s+INTO\s+ONLY\s+""")){"INSERT INTO "}
        val executable=stripLeadingSqlComments(s)
        val isInsert=Regex("""(?is)^\s*(?:INSERT|REPLACE)(?:\s+OR\s+(?:IGNORE|REPLACE))?\s+INTO\b""").containsMatchIn(executable)
        val isCreate=Regex("""(?is)^\s*CREATE\s+(?:TEMP(?:ORARY)?\s+)?TABLE(?:\s+IF\s+NOT\s+EXISTS)?\b""").containsMatchIn(executable)
        // MySQL dumps commonly use large multi-row INSERT statements. SQLite accepts
        // MySQL backtick quoting, so avoid the expensive identifier rewrite pipeline
        // for large INSERT payloads. These statements are executed in bounded row
        // batches later by executeImportInsertBatched().
        if(isInsert && executable.length>=LARGE_IMPORT_STATEMENT_BYTES)return executable.trim() to 0
        // Identifier quoting is safe only outside SQL string literals. This preserves
        // values such as '[dbo].[Customer]' or 'varchar(20)' inside INSERT data.
        s=replaceOutsideSingleQuotes(s, Regex("\\[([^\\]]+)\\]\\.\\[([^\\]]+)\\]")){m->"\\\"${m.groupValues[2].replace("\\\"","\\\"\\\"")}\\\""}
        s=replaceOutsideSingleQuotes(s, Regex("\\[([^\\]]+)\\]")){m->"\\\"${m.groupValues[1].replace("\\\"","\\\"\\\"")}\\\""}
        s=replaceOutsideSingleQuotes(s, Regex("`([^`]+)`")){m->"\\\"${m.groupValues[1].replace("\\\"","\\\"\\\"")}\\\""}
        // Imported workspaces use one SQLite schema, so flatten common source qualifiers
        // for both DDL and DML (CREATE public.t and INSERT INTO public.t).
        s=replaceOutsideSingleQuotes(s, Regex("""(?i)(?:\"(?:public|dbo)\"|\b(?:public|dbo))\s*\.""")){""}
        if(isCreate){
            // Vendor data-type and DDL option normalization is intentionally limited
            // to CREATE TABLE statements so INSERT payloads are never rewritten.
            val typePatterns=linkedMapOf(
                "(?i)\\b(?:nvarchar|varchar|nchar|char|sysname)\\s*\\(\\s*max\\s*\\)" to "TEXT",
                "(?i)\\b(?:nvarchar|varchar|nchar|char|sysname)\\s*\\(\\s*\\d+\\s*\\)" to "TEXT",
                "(?i)\\b(?:nvarchar|varchar|nchar|char|sysname)\\b" to "TEXT",
                "(?i)\\b(?:character\\s+varying|character|bpchar|citext)\\b(?:\\s*\\(\\s*\\d+\\s*\\))?" to "TEXT",
                "(?i)\\b(?:datetime2|datetimeoffset|smalldatetime|datetime|date|time)\\b" to "TEXT",
                "(?i)\\b(?:uniqueidentifier)\\b" to "TEXT",
                "(?i)\\b(?:varbinary|binary|image)\\s*\\(\\s*max\\s*\\)" to "BLOB",
                "(?i)\\b(?:bytea)\\b" to "BLOB",
                "(?i)\\b(?:varbinary|binary)\\s*\\(\\s*\\d+\\s*\\)" to "BLOB",
                "(?i)\\b(?:decimal|numeric|money|smallmoney|float|real|double(?:\\s+precision)?)\\b(?:\\s*\\(\\s*\\d+\\s*(?:,\\s*\\d+\\s*)?\\))?" to "REAL",
                "(?i)\\b(?:bigint|smallint|mediumint|tinyint|integer|int|bit)\\b(?:\\s*\\(\\s*\\d+\\s*\\))?" to "INTEGER ",
                "(?i)\\b(?:text|tinytext|mediumtext|longtext|clob)\\b" to "TEXT",
                "(?i)\\b(?:blob|tinyblob|mediumblob|longblob)\\b" to "BLOB",
                "(?i)\\b(?:boolean|bool)\\b" to "INTEGER",
                "(?i)\\b(?:json|jsonb|uuid|xml|tsvector)\\b(?:\\[\\])?" to "TEXT",
                "(?i)\\b(?:timestamp(?:\\s+with(?:out)?\\s+time\\s+zone)?|timestamptz|time(?:\\s+with(?:out)?\\s+time\\s+zone)?)\\b" to "TEXT",
                "(?i)\\b(?:serial|bigserial|smallserial|serial2|serial4|serial8)\\b" to "INTEGER",
                "(?i)\\bUNSIGNED\\b" to "",
                "(?i)\\bIDENTITY\\s*\\(\\s*\\d+\\s*,\\s*\\d+\\s*\\)" to "",
                "(?i)\\bIDENTITY\\b" to ""
            )
            for((pattern,repl) in typePatterns)s=replaceOutsideSingleQuotes(s,Regex(pattern)){repl}
            s=replaceOutsideSingleQuotes(s,Regex("(?i)\\bPRIMARY\\s+KEY\\s+CLUSTERED\\b")){"PRIMARY KEY"}
            s=replaceOutsideSingleQuotes(s,Regex("(?i)\\bPRIMARY\\s+KEY\\s+NONCLUSTERED\\b")){"PRIMARY KEY"}
            s=replaceOutsideSingleQuotes(s,Regex("(?i)\\bAUTO_INCREMENT\\b")){"AUTOINCREMENT"}
            s=replaceOutsideSingleQuotes(s,Regex("(?i)\\bDEFAULT\\s+GETDATE\\s*\\(\\s*\\)")){"DEFAULT CURRENT_TIMESTAMP"}
            s=replaceOutsideSingleQuotes(s,Regex("(?i)\\bON\\s+UPDATE\\s+CURRENT_TIMESTAMP")){""}
            s=replaceOutsideSingleQuotes(s,Regex("(?i)\\bENGINE\\s*=\\s*[A-Za-z0-9_]+")){""}
            s=replaceOutsideSingleQuotes(s,Regex("(?i)\\bDEFAULT\\s+CHARSET\\s*=\\s*[A-Za-z0-9_]+")){""}
            s=replaceOutsideSingleQuotes(s,Regex("(?i)\\bCOLLATE\\s+[A-Za-z0-9_]+")){""}
            s=replaceOutsideSingleQuotes(s,Regex("(?i)\\bROW_FORMAT\\s*=\\s*[A-Za-z0-9_]+")){""}
            s=replaceOutsideSingleQuotes(s,Regex("(?i)\\bWITH\\s*\\([^)]*\\)")){""}
            s=replaceOutsideSingleQuotes(s,Regex("""(?i)\b(?:ON|TEXTIMAGE_ON)\s+["`]?[_A-Za-z][_A-Za-z0-9]*["`]?""")){""}
            s=replaceOutsideSingleQuotes(s,Regex("(?i)\\b(?:public|dbo)\\s*\\.")){""}
            s=replaceOutsideSingleQuotes(s,Regex("""(?is)([\"`]?[_A-Za-z][_A-Za-z0-9$]*[\"`]?)\s+INTEGER\s+PRIMARY\s+KEY\s*(?!AUTOINCREMENT)""")){m->m.groupValues[1]+" INTEGER PRIMARY KEY AUTOINCREMENT"}
            s=replaceOutsideSingleQuotes(s,Regex("(?i)\\bCLUSTERED\\b")){""}
            s=replaceOutsideSingleQuotes(s,Regex("(?i)\\bNONCLUSTERED\\b")){""}
            s=replaceOutsideSingleQuotes(s,Regex("(?i)\\bCONSTRAINT\\s+[\\\"`\\[]?[^\\s\\\"`\\]]+[\\\"`\\]]?\\s+")){""}
            s=replaceOutsideSingleQuotes(s,Regex("(?is),\\s*(?:(?:UNIQUE\\s+)?(?:FULLTEXT\\s+|SPATIAL\\s+)?KEY)\\s+[\\\"`\\[]?[^\\s\\\"`\\]]+[\\\"`\\]]?\\s*\\([^)]*\\)")){""}
            // SQLite requires AUTOINCREMENT to be part of an INTEGER PRIMARY KEY.
            // Normalize common MySQL/SQL Server forms such as id INTEGER NOT NULL AUTOINCREMENT
            // and remove the duplicate table-level PRIMARY KEY for that same column.
            val auto=Regex("(?is)([\"`]?[_A-Za-z][_A-Za-z0-9$]*[\"`]?)\\s+INTEGER(?:\\s+NOT\\s+NULL)?(?:\\s+PRIMARY\\s+KEY)?\\s+AUTOINCREMENT\\b")
            val autoNames=mutableListOf<String>()
            s=replaceOutsideSingleQuotes(s,auto){m->autoNames.add(m.groupValues[1].trim('\"','`'));m.groupValues[1]+" INTEGER PRIMARY KEY AUTOINCREMENT"}
            for(name in autoNames){
                val duplicatePk=Regex("(?is),\\s*PRIMARY\\s+KEY\\s*\\(\\s*[\"`]?"+Regex.escape(name)+"[\"`]?\\s*\\)\\s*(?=,|\\))")
                s=replaceOutsideSingleQuotes(s,duplicatePk){""}
            }
        } else if(isInsert){
            // Data statements get only transformations that affect SQL syntax, never
            // quoted payload text. SQL Server's Unicode N'...' prefix is harmless in SQLite
            // after removing the prefix; PostgreSQL casts are also removed here.
            s=replaceOutsideSingleQuotes(s,Regex("(?i)\\bN(?=')")){""}
            s=replaceOutsideSingleQuotes(s,Regex("::[A-Za-z_][A-Za-z0-9_]*(?:\\s*\\(\\s*\\d+(?:\\s*,\\s*\\d+)?\\s*\\))?")){""}
        }
        return s.trim() to 0
    }
    private fun fallbackImportStatements(sql:String):List<String>{
        var s=stripLeadingSqlComments(sql).trim()
        if(s.isBlank())return emptyList()
        s=s.replace(Regex("(?is)/\\*!\\d*\\s*"),"").replace(Regex("\\*/"),"")
        s=s.replace(Regex("(?i)\\bIF\\s+NOT\\s+EXISTS\\b"),"IF NOT EXISTS")
        s=replaceOutsideSingleQuotes(s,Regex("(?i)\\bDEFAULT\\s+(?:now\\(\\)|CURRENT_TIMESTAMP\\(\\))")){"DEFAULT CURRENT_TIMESTAMP"}
        s=replaceOutsideSingleQuotes(s,Regex("(?i)\\bBOOLEAN\\s+DEFAULT\\s+(?:true|false)\\b")){m->if(m.value.lowercase(Locale.US).contains("true"))"INTEGER DEFAULT 1" else "INTEGER DEFAULT 0"}
        return splitSql(s)
    }

    private fun normalizeEscapedMySqlBackticks(source:String):String{
        val out=StringBuilder(source.length)
        var inSingle=false
        var i=0
        while(i<source.length){
            val c=source[i]
            if(inSingle){
                out.append(c)
                if(c=='\''){
                    if(i+1<source.length && source[i+1]=='\''){out.append(source[i+1]);i+=2;continue}
                    inSingle=false
                }
                i++
                continue
            }
            if(c=='\''){inSingle=true;out.append(c);i++;continue}
            if(c=='\\' && i+1<source.length && source[i+1]=='`'){
                out.append('`');i+=2;continue
            }
            out.append(c);i++
        }
        return out.toString()
    }

    private fun replaceOutsideSingleQuotes(source:String, regex:Regex, replacement:(MatchResult)->String):String{
        val out=StringBuilder(source.length);var cursor=0
        while(cursor<source.length){
            val quoteStart=source.indexOf('\'',cursor)
            if(quoteStart<0){out.append(regex.replace(source.substring(cursor)){replacement(it)});break}
            if(quoteStart>cursor)out.append(regex.replace(source.substring(cursor,quoteStart)){replacement(it)})
            var i=quoteStart;out.append(source[i]);i++
            while(i<source.length){
                out.append(source[i])
                if(source[i]=='\''){
                    if(i+1<source.length&&source[i+1]=='\''){out.append(source[i+1]);i+=2;continue}
                    i++;break
                }
                i++
            }
            cursor=i
        }
        return out.toString()
    }
    private companion object {
        const val LARGE_IMPORT_STATEMENT_BYTES=256*1024
        const val IMPORT_INSERT_BATCH_ROWS=500
        const val IMPORT_INSERT_BATCH_CHARS=96*1024
        const val IMPORT_COMMIT_ROWS=20000L
        const val IMPORT_COMMIT_STATEMENTS=5000L
    }
    private data class LargeInsertResult(val rows:Long)
    private fun isInsertStatement(sql:String):Boolean = Regex("""(?is)^\s*(?:INSERT|REPLACE)(?:\s+(?:LOW_PRIORITY|HIGH_PRIORITY|IGNORE))*?(?:\s+OR\s+(?:IGNORE|REPLACE))?\s+INTO\b""").containsMatchIn(sql)
    private fun hasUnsafeLargeInsertSuffix(sql:String):Boolean = Regex("""(?is)\)\s+ON\s+DUPLICATE\s+KEY\s+UPDATE\b""").containsMatchIn(sql)
    private fun normalizeLargeInsertPrefix(prefix:String):String = when {
        Regex("""(?is)^\s*INSERT\s+(?:LOW_PRIORITY\s+|HIGH_PRIORITY\s+)?IGNORE\s+INTO\b""").containsMatchIn(prefix) -> Regex("""(?is)^\s*INSERT\s+(?:LOW_PRIORITY\s+|HIGH_PRIORITY\s+)?IGNORE\s+INTO\b""").replaceFirst(prefix,"INSERT OR IGNORE INTO")
        Regex("""(?is)^\s*INSERT\s+(?:LOW_PRIORITY\s+|HIGH_PRIORITY\s+)+INTO\b""").containsMatchIn(prefix) -> Regex("""(?is)^\s*INSERT\s+(?:LOW_PRIORITY\s+|HIGH_PRIORITY\s+)+INTO\b""").replaceFirst(prefix,"INSERT INTO")
        else -> prefix
    }
    private fun normalizeMySqlStringEscapesForSqlite(sql:String):String{
        val out=StringBuilder(sql.length)
        var quote=false;var i=0
        while(i<sql.length){
            val c=sql[i];val next=if(i+1<sql.length)sql[i+1] else '\u0000'
            if(quote){
                if(c=='\\'&&next!='\u0000'){
                    when(next){
                        '\'' -> out.append("''")
                        '\\' -> out.append('\\')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'b' -> out.append('\b')
                        'Z' -> out.append('\u001A')
                        '"' -> out.append('"')
                        else -> out.append(next)
                    }
                    i+=2;continue
                }
                if(c=='\''){if(next=='\''){out.append("''");i+=2;continue};quote=false}
                out.append(c);i++;continue
            }
            if(c=='\''){quote=true;out.append(c);i++;continue}
            if(c=='\\'&&next=='`'){out.append('`');i+=2;continue}
            out.append(c);i++
        }
        return out.toString()
    }
    private fun executeImportInsertBatched(database:SQLiteDatabase,sql:String,onBatchBoundary:()->Unit):LargeInsertResult{
        val valuesMatch=Regex("""(?is)\bVALUES\b""").find(sql)
        val valuesIndex=valuesMatch?.range?.last?.plus(1) ?: -1
        if(valuesIndex<0 || valuesIndex>=sql.length){database.execSQL(sql);return LargeInsertResult(0)}
        var i=valuesIndex
        while(i<sql.length&&sql[i].isWhitespace())i++
        if(i>=sql.length||sql[i]!='('){database.execSQL(sql);return LargeInsertResult(0)}
        val prefix=normalizeLargeInsertPrefix(sql.substring(0,valuesIndex).trimEnd())+" "
        var batch=StringBuilder(minOf(IMPORT_INSERT_BATCH_CHARS,sql.length)+prefix.length)
        batch.append(prefix)
        var firstTuple=true
        var rows=0L
        while(i<sql.length){
            while(i<sql.length&&sql[i].isWhitespace())i++
            if(i>=sql.length)break
            if(sql[i]!='('){database.execSQL(sql);return LargeInsertResult(0)}
            val tupleStart=i
            val tupleEnd=findBalancedTupleEnd(sql,tupleStart)
            if(tupleEnd<0){database.execSQL(sql);return LargeInsertResult(0)}
            if(!firstTuple)batch.append(',')
            batch.append(sql,tupleStart,tupleEnd+1)
            firstTuple=false
            rows++
            i=tupleEnd+1
            while(i<sql.length&&sql[i].isWhitespace())i++
            val done=i>=sql.length||sql[i]==';'
            val nextIsTuple=!done&&sql[i]==','
            if(nextIsTuple)i++
            if(done||!nextIsTuple||rows%IMPORT_INSERT_BATCH_ROWS==0L||batch.length>=IMPORT_INSERT_BATCH_CHARS){
                database.execSQL(normalizeMySqlStringEscapesForSqlite(batch.toString()))
                batch.setLength(0);batch.append(prefix);firstTuple=true
                onBatchBoundary()
                if(done||!nextIsTuple)break
            }
        }
        return LargeInsertResult(rows)
    }
    private fun findBalancedTupleEnd(sql:String,start:Int):Int{
        if(start !in sql.indices||sql[start]!='(')return -1
        var depth=0;var quote:Char?=null;var i=start
        while(i<sql.length){
            val c=sql[i];val next=if(i+1<sql.length)sql[i+1] else '\u0000'
            if(quote!=null){
                if(c=='\\'&&next!='\u0000'){i+=2;continue}
                if(c==quote){if(next==quote){i+=2;continue};quote=null}
                i++;continue
            }
            when(c){
                '\'', '\"' -> quote=c
                '(' -> depth++
                ')' -> {depth--;if(depth==0)return i}
            }
            i++
        }
        return -1
    }

    private fun parseImportInsert(sql:String):ParsedInsert?{
        // Parse INSERT statements structurally instead of relying on one large regex.
        // This is deliberately tolerant of SQL Server/MySQL/PostgreSQL dump spelling:
        //   INSERT INTO [dbo].[Customer] ([Id], [Name])VALUES(...)
        //   INSERT IGNORE INTO `Customer` (`Id`,`Name`) VALUES (...)
        //   INSERT INTO "Customer" ("Id","Name") VALUES (...)
        var s=stripLeadingSqlComments(sql).trim().removeSuffix(";").trim()
        if(s.isBlank())return null
        val prefix=Regex("(?is)^INSERT\\s+(?:(?:LOW_PRIORITY|HIGH_PRIORITY|IGNORE)\\s+)*(?:OR\\s+(?:IGNORE|REPLACE)\\s+)?INTO\\s+").find(s) ?: return null
        var i=prefix.range.last+1
        fun skipWs(){while(i<s.length&&s[i].isWhitespace())i++}
        fun readIdentifier():String?{
            skipWs();if(i>=s.length)return null
            val start=i
            val c=s[i]
            if(c=='['){
                i++;val b=i;while(i<s.length&&s[i]!=']')i++;if(i>=s.length)return null
                val out=s.substring(b,i);i++;return out
            }
            if(c=='`'||c=='"'){
                val q=c;i++;val out=StringBuilder()
                while(i<s.length){val ch=s[i];if(ch==q){if(i+1<s.length&&s[i+1]==q){out.append(q);i+=2;continue};i++;return out.toString()};out.append(ch);i++}
                return null
            }
            while(i<s.length&&(s[i].isLetterOrDigit()||s[i]=='_'||s[i]=='$'))i++
            return if(i>start)s.substring(start,i) else null
        }
        val first=readIdentifier() ?: return null
        skipWs()
        var table=first
        if(i<s.length&&s[i]=='.'){
            i++;val second=readIdentifier() ?: return null;table=second
        }
        skipWs()
        var columns=emptyList<String>()
        if(i<s.length&&s[i]=='('){
            val tuple=extractBalanced(s,i) ?: return null
            columns=splitTopLevelComma(tuple).map{it.trim().trim('`','"','[',']')}.filter{it.isNotBlank()}
            i += tuple.length+2 // opening '(' + inner text + closing ')'
        }
        skipWs()
        val valuesMatch=Regex("(?is)^VALUES\\b").find(s,i) ?: return null
        i=valuesMatch.range.last+1
        val valuesPart=s.substring(i).trim()
        val firstTupleStart=valuesPart.indexOf('(')
        if(firstTupleStart<0)return null
        val tuple=extractBalanced(valuesPart,firstTupleStart) ?: return null
        val values=splitTopLevelComma(tuple).map{normalizeImportLiteral(it.trim())}
        val cols=if(columns.isNotEmpty())columns else values.indices.map{"column_${it+1}"}
        if(cols.size!=values.size)return null
        return ParsedInsert(table,cols,values)
    }

    private fun splitTopLevelComma(s:String):List<String>{
        val out=mutableListOf<String>();var q:Char?=null;var depth=0;var start=0;var i=0
        while(i<s.length){val c=s[i];if(q!=null){if(c==q){if(i+1<s.length&&s[i+1]==q){i+=2;continue};q=null};i++;continue};if(c=='\''||c=='"'||c=='`'){q=c;i++;continue};when(c){'('->depth++;')'->if(depth>0)depth--;','->if(depth==0){out.add(s.substring(start,i));start=i+1}};i++}
        out.add(s.substring(start));return out
    }
    private fun extractBalanced(s:String,start:Int):String?{if(start !in s.indices||s[start]!='(')return null;var depth=0;var q:Char?=null;var i=start;while(i<s.length){val c=s[i];if(q!=null){if(c==q){if(i+1<s.length&&s[i+1]==q){i+=2;continue};q=null};i++;continue};if(c=='\''||c=='"'||c=='`'){q=c;i++;continue};if(c=='(')depth++;if(c==')'){depth--;if(depth==0)return s.substring(start+1,i)};i++};return null}
    private fun normalizeImportLiteral(v:String):String{val t=v.trim();return when{t.matches(Regex("(?i)^N'.*'$"))->t.substring(1);t.equals("NULL",true)->"";else->t.trim('`')}}
    private data class ImportOutcome(val database:SQLiteDatabase,val target:File,val label:String,val status:String)
    private class ImportCancelledException:Exception("Import cancelled")
    private fun showImportProgress(title:String,fileName:String){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(4),dp(4),dp(4),dp(2))}
        importTitleView=TextView(this).apply{text=title;textSize=14f;setTypeface(null,android.graphics.Typeface.BOLD);setTextColor(white);setPadding(0,dp(4),0,dp(2))}
        importDetailView=TextView(this).apply{text=fileName;textSize=11f;setTextColor(Color.rgb(145,141,154));setPadding(0,dp(2),0,dp(8));maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END}
        importDots=LoadingDotsView(this).apply{layoutParams=LinearLayout.LayoutParams(dp(92),dp(32)).apply{gravity=android.view.Gravity.CENTER_HORIZONTAL};setPadding(0,0,0,0)}
        importCancelButton=Button(ContextThemeWrapper(this,R.style.FlatButton)).apply{text="Cancel";isAllCaps=false;setOnClickListener{importCancelled=true;text="Stopping…";isEnabled=false}}
        box.addView(importTitleView);box.addView(importDetailView);box.addView(importDots);box.addView(importCancelButton,LinearLayout.LayoutParams(-1,dp(38)).apply{topMargin=dp(6)})
        importDialog=cardDialog("",box).apply{setCanceledOnTouchOutside(false);setCancelable(false)}
        importDialog?.show()
        importDots?.post{if(importInProgress)importDots?.start()}
    }
    private fun closeImportDialog(){importDots?.stop();importDialog?.dismiss();importDialog=null;importDots=null;importCancelButton=null;importTitleView=null;importDetailView=null}
    private fun finishImportedDatabase(outcome:ImportOutcome){
        closeImportDialog()
        try{if(::db.isInitialized&&db.isOpen)db.close()}catch(_:Exception){}
        db=outcome.database;dbFile=outcome.target;configureDatabase(db)
        val paths=userDatabasePaths();paths.add(outcome.target.path);saveUserDatabasePaths(paths)
        prefs.edit().putString("active_db_path",outcome.target.path).putString("db_label_${outcome.target.path.hashCode()}",outcome.label).apply()
        clearResults();showPlaceholder();refreshSchemaPanel();val first=firstTable();editor.setText(if(first.isNotBlank())"SELECT * FROM \"${first.replace("\"","\"\"")}\";" else "");editor.setSelection(editor.length())
        importInProgress=false;statusOk(outcome.status)
    }
    private fun showImportCancelled(){closeImportDialog();importInProgress=false;statusOk("Import cancelled — active database was unchanged")}
    private fun showImportFailure(message:String,uri:Uri,fileName:String){closeImportDialog();importInProgress=false;val detail=TextView(this).apply{setText("$message\n\n$fileName");textSize=12f;setTextColor(white);setPadding(0,dp(8),0,dp(8));setTextIsSelectable(true)};cardDialog("Import failed",detail).show()}
    private fun checkImportCancelled(){if(importCancelled)throw ImportCancelledException()}
    private fun copyStreamToFile(input:InputStream,target:File){
        target.parentFile?.mkdirs()
        input.buffered(1024*1024).use{src->FileOutputStream(target).use{rawOut->BufferedOutputStream(rawOut,1024*1024).use{out->
            val buffer=ByteArray(1024*1024);var total=0L;var lastUi=0L;while(true){checkImportCancelled();val n=src.read(buffer);if(n<0)break;out.write(buffer,0,n);total+=n;if(total-lastUi>=64L*1024L*1024L){lastUi=total;val text="Streaming from disk · ${humanBytes(total)} processed";runOnUiThread{if(importInProgress)importDetailView?.text=text}}};out.flush();rawOut.fd.sync()
        }}}
    }
    private fun importDatabaseStream(input:InputStream,fileName:String):ImportOutcome{
        checkImportCancelled()
        val target=File(filesDir,"Imported_${System.currentTimeMillis()}_${fileName.replace(Regex("[^A-Za-z0-9._-]"),"_")}")
        try{copyStreamToFile(input,target)}catch(e:Exception){target.delete();throw e}
        val opened=try{SQLiteDatabase.openDatabase(target.path,null,SQLiteDatabase.OPEN_READWRITE)}catch(e:Exception){target.delete();throw Exception("Could not open SQLite database: ${e.message?:"invalid database"}")}
        try{configureDatabase(opened)}catch(_:Exception){}
        try{
            if(!hasUserTables(opened))throw Exception("The database opened successfully but contains no user tables")
            checkImportCancelled()
            return ImportOutcome(opened,target,fileName.substringBeforeLast('.',fileName),"Imported database: $fileName · ${humanBytes(target.length())}")
        }catch(e:Exception){opened.close();target.delete();throw e}
    }
    private data class StreamTableSpec(val columns:LinkedHashSet<String> = linkedSetOf(), val samples:LinkedHashMap<String,MutableList<String>> = linkedMapOf())
    private data class CopyImport(val table:String,val columns:List<String>,val rows:List<List<String>>)
    private fun parsePostgresCopyBlock(sql:String):CopyImport?{
        val lines=sql.replace("\r\n","\n").replace('\r','\n').split('\n')
        val head=lines.firstOrNull()?.trim().orEmpty()
        val m=Regex("(?is)^COPY\\s+(.+?)(?:\\s*\\((.*?)\\))?\\s+FROM\\s+stdin\\s*;?$").find(head) ?: return null
        val rawTable=m.groupValues[1].trim().trim('`','"','[',']').substringAfterLast('.')
        val cols=m.groupValues.getOrNull(2)?.takeIf{it.isNotBlank()}?.let{splitTopLevelComma(it).map{v->v.trim().trim('`','"','[',']')}} ?: emptyList()
        val data=mutableListOf<List<String>>()
        for(line in lines.drop(1)){
            if(line.trim()=="\\.")break
            if(line.isBlank())continue
            data.add(line.split('\t').map{v->if(v=="\\N")"" else v.replace("\\t","\t").replace("\\n","\n").replace("\\\\","\\")})
        }
        return if(data.isEmpty())null else CopyImport(rawTable,cols,data)
    }
    private fun copyBlockToInserts(copy:CopyImport):List<String>{
        val cols=if(copy.columns.isNotEmpty())" ("+copy.columns.joinToString(", "){"\"${it.replace("\"","\"\"")}\""}+")" else ""
        return copy.rows.map{row->
            val values=row.joinToString(", "){v->if(v.isEmpty())"NULL" else "'${v.replace("'","''")}'"}
            "INSERT INTO \"${copy.table.replace("\"","\"\"")}\"$cols VALUES ($values);"
        }
    }

    private fun importSqlStream(uri:Uri,fileName:String):ImportOutcome{
        // MySQL dumps can contain a single multi-row INSERT spanning tens of megabytes.
        // Detect the native MySQL dump header first and use the bounded-memory single-pass
        // importer for those files; this avoids ever materializing the giant INSERT.
        if (isLikelyMySqlDump(uri)) {
            return importMySqlDumpStreaming(uri,fileName)
        }
        // Fallback for generic SQL / INSERT-only dumps keeps the existing two-pass path.
        var hasCreate=false;var hasInsert=false
        val specs=linkedMapOf<String,StreamTableSpec>()
        contentResolver.openInputStream(uri)?.use{input->streamSqlStatements(input){raw->
            checkImportCancelled()
            val normalized=prepareSqlForSQLite(raw).first
            if(normalized.length>=LARGE_IMPORT_STATEMENT_BYTES&&isInsertStatement(normalized)){
                hasInsert=true
                if(!hasCreate){
                    val row=parseImportInsert(normalized)
                    if(row!=null){
                        val spec=specs.getOrPut(row.table){StreamTableSpec()}
                        row.columns.forEachIndexed{idx,col->
                            spec.columns.add(col)
                            val list=spec.samples.getOrPut(col){mutableListOf()}
                            if(idx<row.values.size&&list.size<32)list.add(row.values[idx])
                        }
                    }
                }
                return@streamSqlStatements
            }
            for(piece in splitSql(normalized)){
                val st=piece.trim();if(st.isBlank()||shouldSkipImportStatement(st)||isQueryStatement(st))continue
                if(Regex("""(?is)^CREATE\s+TABLE\b""").containsMatchIn(st))hasCreate=true
                if(Regex("""(?is)^INSERT\s+(?:OR\s+(?:IGNORE|REPLACE)\s+)?INTO\b""").containsMatchIn(st)){
                    hasInsert=true
                    if(!hasCreate){
                        val row=parseImportInsert(st)
                        if(row!=null){
                            val spec=specs.getOrPut(row.table){StreamTableSpec()}
                            row.columns.forEachIndexed{idx,col->
                                spec.columns.add(col)
                                val list=spec.samples.getOrPut(col){mutableListOf()}
                                if(idx<row.values.size&&list.size<32)list.add(row.values[idx])
                            }
                        }
                    }
                }
                val copy=parsePostgresCopyBlock(st)
                if(copy!=null){
                    hasInsert=true
                    val spec=specs.getOrPut(copy.table){StreamTableSpec()}
                    val cols=if(copy.columns.isNotEmpty())copy.columns else copy.rows.firstOrNull()?.indices?.map{"column_${it+1}"}.orEmpty()
                    cols.forEachIndexed{idx,col->
                        spec.columns.add(col)
                        val list=spec.samples.getOrPut(col){mutableListOf()}
                        copy.rows.take(32).forEach{r->if(idx<r.size&&list.size<32)list.add(r[idx])}
                    }
                }
            }
        }} ?: throw Exception("Could not read $fileName")
        return importSqlStreamSecondPass(uri,fileName,hasCreate,hasInsert,specs)
    }
    private fun isLikelyMySqlDump(uri:Uri):Boolean{
        return try{
            contentResolver.openInputStream(uri)?.use{input->
                val bytes=ByteArray(16*1024);val n=input.read(bytes)
                if(n<=0)return@use false
                val head=String(bytes,0,n,Charsets.UTF_8).lowercase(Locale.US)
                head.contains("mysql dump") && head.contains("server version")
            } ?: false
        }catch(_:Exception){false}
    }

    private fun importMySqlDumpStreaming(uri:Uri,fileName:String):ImportOutcome{
        val target=File(filesDir,"Imported_${System.currentTimeMillis()}_${fileName.replace(Regex("[^A-Za-z0-9._-]"),"_")}.db")
        var fresh:SQLiteDatabase?=null
        var statements=0
        var skipped=0
        var rows=0L
        var sinceCommitRows=0L
        var sinceCommitStatements=0L
        var inTransaction=false
        try{
            fresh=SQLiteDatabase.openOrCreateDatabase(target,null);configureDatabase(fresh)
            fresh.beginTransaction();inTransaction=true

            streamMySqlDumpUnits(contentResolver.openInputStream(uri)?:throw Exception("Could not reopen $fileName")){unit->
                checkImportCancelled()
                when(unit){
                    is MySqlDumpUnit.Small->{
                        val prepared=prepareSqlForSQLite(unit.sql);skipped+=prepared.second
                        val normalized=prepared.first.trim();if(normalized.isBlank())return@streamMySqlDumpUnits
                        for(piece in splitSql(normalized)){
                            checkImportCancelled();val st=piece.trim();if(st.isBlank()||isQueryStatement(st))continue
                            if(shouldSkipImportStatement(st)){skipped++;continue}
                            try{fresh!!.execSQL(st)}catch(first:Exception){
                                var success=false;var last:Exception=first
                                val candidates=linkedSetOf<String>();val retry=st.removeSuffix(";").trim();if(retry.isNotBlank())candidates.add(retry)
                                fallbackImportStatements(st).forEach{candidate->if(candidate.isNotBlank())candidates.add(candidate.trim().removeSuffix(";").trim())}
                                for(candidate in candidates){if(candidate==st)continue;try{fresh!!.execSQL(candidate);success=true;break}catch(e:Exception){last=e}}
                                if(!success)throw last
                            }
                            statements++
                            sinceCommitStatements++
                            if(sinceCommitStatements>=IMPORT_COMMIT_STATEMENTS){
                                fresh!!.setTransactionSuccessful();fresh!!.endTransaction();inTransaction=false
                                fresh!!.beginTransaction();inTransaction=true;sinceCommitRows=0L;sinceCommitStatements=0L
                            }
                        }
                    }
                    is MySqlDumpUnit.InsertBatch->{
                        if(unit.sql.isBlank()||unit.rows<=0L)return@streamMySqlDumpUnits
                        fresh!!.execSQL(unit.sql)
                        rows+=unit.rows
                        statements++
                        sinceCommitRows+=unit.rows
                        sinceCommitStatements++
                        if(sinceCommitRows>=IMPORT_COMMIT_ROWS||sinceCommitStatements>=IMPORT_COMMIT_STATEMENTS){
                            fresh!!.setTransactionSuccessful();fresh!!.endTransaction();inTransaction=false
                            fresh!!.beginTransaction();inTransaction=true;sinceCommitRows=0L;sinceCommitStatements=0L
                        }
                        runOnUiThread{if(importInProgress)importDetailView?.text="Imported $rows rows · streaming MySQL INSERTs"}
                    }
                }
            }
            checkImportCancelled();if(inTransaction){fresh.setTransactionSuccessful();fresh.endTransaction();inTransaction=false}
            if(!hasUserTables(fresh))throw Exception("No user tables were created")
            val suffix=if(skipped>0)" · skipped $skipped unsupported/dump-control statement(s)" else ""
            return ImportOutcome(fresh,target,fileName.substringBeforeLast('.',fileName),"Imported $fileName · ${humanBytes(target.length())} · $statements statement(s) · $rows row inserts$suffix").also{fresh=null}
        }catch(e:Exception){
            try{if(inTransaction)fresh?.endTransaction()}catch(_:Exception){}
            try{fresh?.close()}catch(_:Exception){}
            target.delete()
            if(e is ImportCancelledException)throw e
            throw Exception("Import failed after $statements statement(s)${if(rows>0)" and $rows row inserts" else ""}: ${e.message?:"SQL error"}")
        }
    }

    private sealed class MySqlDumpUnit{
        data class Small(val sql:String):MySqlDumpUnit()
        data class InsertBatch(val sql:String,val rows:Long):MySqlDumpUnit()
    }

    private fun normalizeMySqlInsertPrefixStreaming(prefix:String):String{
        var p=prefix.trim()
        p=Regex("(?is)^INSERT\\s+(?:LOW_PRIORITY\\s+|HIGH_PRIORITY\\s+)?IGNORE\\s+INTO\\b").replaceFirst(p,"INSERT OR IGNORE INTO")
        p=Regex("(?is)^INSERT\\s+(?:LOW_PRIORITY\\s+|HIGH_PRIORITY\\s+)+INTO\\b").replaceFirst(p,"INSERT INTO")
        p=Regex("(?is)^REPLACE\\s+INTO\\b").replaceFirst(p,"INSERT OR REPLACE INTO")
        p=normalizeEscapedMySqlBackticks(p)
        p=p.replace('`','"')
        return p+" VALUES "
    }

    private fun streamMySqlDumpUnits(input:InputStream,onUnit:(MySqlDumpUnit)->Unit){
        openSqlReader(input).use{r->
            val small=StringBuilder(16*1024)
            var quote:Char?=null
            var backtick=false
            var bracket=false
            var lineComment=false
            var blockComment=false
            var parenDepth=0
            var mode=0 // 0 small statement, 1 INSERT prefix, 2 INSERT tuples
            var insertHeader=StringBuilder(1024)
            var insertPrefix=""
            var tuple=StringBuilder(4096)
            var batch=StringBuilder(IMPORT_INSERT_BATCH_CHARS+256)
            var batchRows=0L
            var tupleDepth=0
            var tupleQuote:Char?=null
            var tupleEscape=false
            var sawTuple=false
            var pendingAfterTuple=false
            var recent=StringBuilder(64)

            fun flushSmall(){
                if(small.isNotBlank()){onUnit(MySqlDumpUnit.Small(small.toString()));small.setLength(0)}
            }
            fun flushInsertBatch(){
                if(batchRows<=0L)return
                onUnit(MySqlDumpUnit.InsertBatch(normalizeMySqlStringEscapesForSqlite(batch.toString()),batchRows))
                batch.setLength(0);batch.append(insertPrefix);batchRows=0L;sawTuple=false
            }
            fun resetInsert(){
                mode=0;insertHeader.setLength(0);insertPrefix="";tuple.setLength(0);batch.setLength(0);batchRows=0L;tupleDepth=0;tupleQuote=null;tupleEscape=false;sawTuple=false;pendingAfterTuple=false;recent.setLength(0)
            }
            fun finishTuple(){
                if(tuple.isNotBlank()){
                    if(sawTuple)batch.append(',')
                    batch.append(tuple)
                    sawTuple=true;batchRows++
                    tuple.setLength(0);tupleDepth=0;tupleQuote=null;tupleEscape=false
                    if(batchRows>=IMPORT_INSERT_BATCH_ROWS||batch.length>=IMPORT_INSERT_BATCH_CHARS)flushInsertBatch()
                }
            }
            fun processTupleChar(c:Char){
                if(tupleQuote!=null){
                    tuple.append(c)
                    if(tupleEscape){tupleEscape=false;return}
                    if(c=='\\'){tupleEscape=true;return}
                    if(c==tupleQuote){tupleQuote=null}
                    return
                }
                if(c=='\''||c=='"'){tupleQuote=c;tuple.append(c);return}
                when(c){
                    '('->{tupleDepth++;tuple.append(c)}
                    ')'->{
                        if(tupleDepth<=0)throw Exception("Malformed MySQL INSERT tuple")
                        tupleDepth--;tuple.append(c)
                        if(tupleDepth==0){finishTuple();pendingAfterTuple=true}
                    }
                    ','->{if(pendingAfterTuple){pendingAfterTuple=false}else tuple.append(c)}
                    ';'->{
                        if(pendingAfterTuple){
                            pendingAfterTuple=false;flushInsertBatch();resetInsert()
                        }else tuple.append(c)
                    }
                    else->{
                        if(!pendingAfterTuple)tuple.append(c)
                        else if(!c.isWhitespace())throw Exception("Unsupported MySQL INSERT syntax near '${recent.toString().takeLast(48)}'")
                    }
                }
            }
            fun processHeaderChar(c:Char){
                insertHeader.append(c)
                recent.append(c);if(recent.length>64)recent.delete(0,recent.length-64)
                val h=insertHeader.toString()
                val m=Regex("(?is)\\bVALUES\\b").find(h)
                if(m!=null){
                    insertPrefix=normalizeMySqlInsertPrefixStreaming(h.substring(0,m.range.first))
                    batch.append(insertPrefix)
                    val rest=h.substring(m.range.last+1)
                    mode=2;insertHeader.setLength(0)
                    for(ch in rest)processTupleChar(ch)
                }
            }
            fun maybeSwitchToInsert():Boolean{
                val t=small.toString().trimStart()
                if(t.length<8)return false
                if(!Regex("(?is)^(?:INSERT\\s+(?:(?:LOW_PRIORITY|HIGH_PRIORITY|IGNORE)\\s+)*(?:OR\\s+(?:IGNORE|REPLACE)\\s+)?INTO\\b|REPLACE\\s+INTO\\b)").containsMatchIn(t))return false
                insertHeader.append(small);small.setLength(0);mode=1;return true
            }

            val buf=CharArray(64*1024)
            while(true){
                checkImportCancelled();val n=r.read(buf);if(n<0)break
                var i=0
                while(i<n){
                    val c=buf[i]
                    when(mode){
                        1->{processHeaderChar(c);i++}
                        2->{processTupleChar(c);i++}
                        else->{
                            if(lineComment){small.append(c);if(c=='\n'){lineComment=false;flushSmall()};i++;continue}
                            if(blockComment){
                                small.append(c)
                                if(c=='*'&&i+1<n&&buf[i+1]=='/'){small.append('/');i+=2;blockComment=false;continue}
                                i++;continue
                            }
                            if(quote!=null){
                                small.append(c)
                                if(c=='\\'&&i+1<n){small.append(buf[i+1]);i+=2;continue}
                                if(c==quote){if(i+1<n&&buf[i+1]==quote){small.append(buf[i+1]);i+=2;continue};quote=null}
                                i++;continue
                            }
                            if(backtick){
                                small.append(c)
                                if(c=='`'){if(i+1<n&&buf[i+1]=='`'){small.append(buf[i+1]);i+=2;continue};backtick=false}
                                i++;continue
                            }
                            if(bracket){small.append(c);if(c==']')bracket=false;i++;continue}
                            if(c=='-'&&i+1<n&&buf[i+1]=='-'){small.append(c);small.append(buf[i+1]);i+=2;lineComment=true;continue}
                            if(c=='#'){small.append(c);i++;lineComment=true;continue}
                            if(c=='/'&&i+1<n&&buf[i+1]=='*'){small.append(c);small.append(buf[i+1]);i+=2;blockComment=true;continue}
                            if(c=='\''||c=='"'){quote=c;small.append(c);i++;continue}
                            if(c=='`'){backtick=true;small.append(c);i++;continue}
                            if(c=='['){bracket=true;small.append(c);i++;continue}
                            small.append(c)
                            if(small.length<=512&&maybeSwitchToInsert()){i++;continue}
                            if(c=='(')parenDepth++ else if(c==')'&&parenDepth>0)parenDepth--
                            if(c==';'&&parenDepth==0){flushSmall()}
                            i++
                        }
                    }
                }
            }
            when(mode){
                2->{if(tupleQuote!=null||tupleDepth!=0)throw Exception("Unterminated MySQL INSERT statement");flushInsertBatch();resetInsert()}
                1->throw Exception("Incomplete MySQL INSERT statement: VALUES clause not found")
                else->flushSmall()
            }
        }
    }

    private fun importSqlStreamSecondPass(uri:Uri,fileName:String,hasCreate:Boolean,hasInsert:Boolean,specs:LinkedHashMap<String,StreamTableSpec>):ImportOutcome{
        if(!hasCreate && !hasInsert)throw Exception("No CREATE TABLE or INSERT statements were found")
        val target=File(filesDir,"Imported_${System.currentTimeMillis()}_${fileName.replace(Regex("[^A-Za-z0-9._-]"),"_")}.db")
        var fresh:SQLiteDatabase?=null;var statements=0;var skipped=0;var rows=0L;var inTransaction=false
        try{
            fresh=SQLiteDatabase.openOrCreateDatabase(target,null);configureDatabase(fresh)
            fresh.beginTransaction();inTransaction=true
            if(!hasCreate&&hasInsert){
                for((table,spec) in specs){checkImportCancelled();val defs=spec.columns.joinToString(", "){col->
                    val type=inferImportType(spec.samples[col].orEmpty());val pk=if(col.equals("id",true)&&type=="INTEGER")" PRIMARY KEY" else "";"\"${col.replace("\"","\"\"")}\" $type$pk"}
                    if(defs.isNotBlank())fresh.execSQL("CREATE TABLE \"${table.replace("\"","\"\"")}\" ($defs)")
                }
            }
            var sinceCommitRows=0L
            var sinceCommitStatements=0L
            streamSqlStatements(contentResolver.openInputStream(uri)?:throw Exception("Could not reopen $fileName")){raw->
                checkImportCancelled();val prepared=prepareSqlForSQLite(raw);skipped+=prepared.second
                val normalized=prepared.first.trim()
                if(normalized.isBlank())return@streamSqlStatements
                if(isInsertStatement(normalized)&&normalized.length>=LARGE_IMPORT_STATEMENT_BYTES){
                    if(hasUnsafeLargeInsertSuffix(normalized)){
                        fresh!!.execSQL(normalized)
                        statements++
                        runOnUiThread{if(importInProgress)importDetailView?.text="Imported $rows rows"}
                        return@streamSqlStatements
                    }
                    val result=executeImportInsertBatched(fresh!!,normalized){checkImportCancelled()}
                    statements++
                    rows+=result.rows
                    sinceCommitRows+=result.rows
                    sinceCommitStatements++
                    if(sinceCommitRows>=IMPORT_COMMIT_ROWS){
                        fresh!!.setTransactionSuccessful();fresh!!.endTransaction();inTransaction=false;fresh!!.beginTransaction();inTransaction=true;sinceCommitRows=0L;sinceCommitStatements=0L
                    }
                    runOnUiThread{if(importInProgress)importDetailView?.text="Imported $rows rows · streaming large INSERTs"}
                    return@streamSqlStatements
                }
                for(piece in splitSql(normalized)){
                    checkImportCancelled();var st=piece.trim();if(st.isBlank()||isQueryStatement(st))continue
                    if(shouldSkipImportStatement(st)){skipped++;continue}
                    val copyBlock=parsePostgresCopyBlock(st)
                    if(copyBlock!=null){
                        copyBlockToInserts(copyBlock).forEach{copySql->fresh!!.execSQL(copySql);statements++;rows++;sinceCommitRows++;sinceCommitStatements++}
                        continue
                    }
                    if(!hasCreate&&hasInsert){val parsed=parseImportInsert(st);if(parsed==null)continue}
                    try{
                        fresh!!.execSQL(st)
                    }catch(first:Exception){
                        var success=false;var last:Exception=first
                        val candidates=linkedSetOf<String>()
                        val retry=st.trim().removeSuffix(";").trim();if(retry.isNotBlank())candidates.add(retry)
                        fallbackImportStatements(st).forEach{candidate->if(candidate.trim().isNotBlank())candidates.add(candidate.trim().removeSuffix(";").trim())}
                        for(candidate in candidates){if(candidate==st.trim())continue;try{fresh!!.execSQL(candidate);success=true;break}catch(e:Exception){last=e}}
                        if(!success)throw last
                    }
                    statements++;if(st.startsWith("INSERT",true)||st.startsWith("REPLACE",true))rows++;sinceCommitRows++;sinceCommitStatements++
                    if(sinceCommitStatements>=IMPORT_COMMIT_STATEMENTS||sinceCommitRows>=IMPORT_COMMIT_ROWS){fresh!!.setTransactionSuccessful();fresh!!.endTransaction();inTransaction=false;fresh!!.beginTransaction();inTransaction=true;sinceCommitRows=0L;sinceCommitStatements=0L}
                }
            }
            checkImportCancelled();if(inTransaction){fresh.setTransactionSuccessful();fresh.endTransaction();inTransaction=false}
            if(!hasUserTables(fresh)){
                if(hasInsert && specs.isNotEmpty()) throw Exception("SQL data was detected and ${specs.size} table schema(s) were inferred, but SQLite created no user tables. Import was rolled back.")
                throw Exception("No user tables were created")
            }
            val suffix=if(skipped>0)" · skipped $skipped unsupported/dump-control statement(s)" else ""
            return ImportOutcome(fresh,target,fileName.substringBeforeLast('.',fileName),"Imported $fileName · ${humanBytes(target.length())} · $statements statement(s) · $rows row inserts$suffix").also{fresh=null}
        }catch(e:Exception){
            try{if(inTransaction)fresh?.endTransaction()}catch(_:Exception){};try{fresh?.close()}catch(_:Exception){};target.delete();if(e is ImportCancelledException)throw e;throw Exception("Import failed after $statements statement(s): ${e.message?:"SQL error"}")
        }
    }
    private fun openSqlReader(input:InputStream):Reader{
        val push=java.io.PushbackInputStream(java.io.BufferedInputStream(input,64*1024),3);val b=ByteArray(3);var n=0
        while(n<3){val v=push.read();if(v<0)break;b[n++]=v.toByte()}
        if(n>=3&&(b[0].toInt() and 255)==0xEF&&(b[1].toInt() and 255)==0xBB&&(b[2].toInt() and 255)==0xBF)return InputStreamReader(push,Charsets.UTF_8)
        if(n>=2&&(b[0].toInt() and 255)==0xFF&&(b[1].toInt() and 255)==0xFE){if(n>2)push.unread(b,2,n-2);return InputStreamReader(push,Charsets.UTF_16LE)}
        if(n>=2&&(b[0].toInt() and 255)==0xFE&&(b[1].toInt() and 255)==0xFF){if(n>2)push.unread(b,2,n-2);return InputStreamReader(push,Charsets.UTF_16BE)}
        push.unread(b,0,n);return InputStreamReader(push,Charsets.UTF_8)
    }
    private fun streamSqlStatements(input:InputStream,onStatement:(String)->Unit){
        // Chunked character scanning avoids BufferedReader.readLine(), which can
        // allocate a huge String when a SQL dump contains a very long INSERT line.
        // The statement buffer grows only to the size of the current SQL statement.
        openSqlReader(input).use{r->
            val current=StringBuilder(16*1024)
            val linePrefix=StringBuilder(256)
            var lineStartIndex=0
            var quote:Char?=null
            var bracket=false
            var backtick=false
            var lineComment=false
            var blockComment=false
            var parenDepth=0
            var delimiter=";"
            var copyMode=false
            var atLineStart=true
            var onlyWhitespaceOnLine=true

            fun flush(){
                if(current.isNotBlank()){onStatement(current.toString());current.setLength(0)}
            }
            fun suffixMatches(value:String):Boolean{
                if(value.isEmpty()||current.length<value.length)return false
                val offset=current.length-value.length
                for(j in value.indices)if(current[offset+j]!=value[j])return false
                return true
            }
            fun processLineEnd(){
                val trimmed=linePrefix.toString().trim()
                if(quote==null&&!bracket&&!backtick&&!blockComment&&parenDepth==0){
                    val upper=trimmed.uppercase(Locale.US)
                    when{
                        Regex("(?i)^DELIMITER\\s+(.+?)\\s*$").matches(trimmed)->{
                            val dm=Regex("(?i)^DELIMITER\\s+(.+?)\\s*$").matchEntire(trimmed)
                            if(dm!=null){
                                current.setLength(lineStartIndex)
                                delimiter=dm.groupValues[1].trim()
                                if(delimiter.isEmpty())delimiter=";"
                            }
                        }
                        trimmed.matches(Regex("(?i)^GO(?:\\s+\\d+)?\\s*$"))->{
                            current.setLength(lineStartIndex);flush()
                        }
                        trimmed.startsWith("SET ",true)||trimmed.startsWith("USE ",true)->flush()
                        trimmed.startsWith("COPY ",true)&&Regex("(?is)\\s+FROM\\s+stdin\\s*;?\\s*$").containsMatchIn(trimmed)->copyMode=true
                    }
                    if(upper.startsWith("IF EXISTS")||upper.startsWith("IF NOT EXISTS")){
                        // Keep vendor IF blocks intact; their actual termination is handled
                        // by the normal delimiter/paren scanner below.
                    }
                    if(copyMode&&trimmed=="\\."){
                        flush();copyMode=false
                    }
                }
                linePrefix.setLength(0)
                lineStartIndex=current.length
                atLineStart=true
                onlyWhitespaceOnLine=true
            }

            val buffer=CharArray(64*1024)
            while(true){
                checkImportCancelled()
                val n=r.read(buffer)
                if(n<0)break
                var i=0
                while(i<n){
                    checkImportCancelled()
                    val c=buffer[i]
                    val next=if(i+1<n)buffer[i+1] else '\u0000'
                    if(copyMode){
                        current.append(c)
                        if(atLineStart && linePrefix.length<256){linePrefix.append(c);if(!c.isWhitespace())onlyWhitespaceOnLine=false}
                        if(c=='\n')processLineEnd()
                        i++;continue
                    }
                    if(lineComment){
                        current.append(c)
                        if(atLineStart){atLineStart=false;lineStartIndex=current.length-1}
                        if(linePrefix.length<256)linePrefix.append(c)
                        if(c=='\n'){lineComment=false;processLineEnd()}else if(!c.isWhitespace())onlyWhitespaceOnLine=false
                        i++;continue
                    }
                    if(blockComment){
                        current.append(c)
                        if(linePrefix.length<256)linePrefix.append(c)
                        if(c=='*'&&next=='/'){current.append(next);if(linePrefix.length<254)linePrefix.append(next);i+=2;blockComment=false;continue}
                        if(c=='\n')processLineEnd() else if(!c.isWhitespace())onlyWhitespaceOnLine=false
                        i++;continue
                    }
                    if(quote!=null){
                        current.append(c)
                        if(c=='\\'&&next!='\u0000'){current.append(next);i+=2;continue}
                        if(c==quote){if(next==quote){current.append(next);i+=2;continue};quote=null}
                        if(c=='\n')processLineEnd() else if(!c.isWhitespace())onlyWhitespaceOnLine=false
                        i++;continue
                    }
                    if(bracket){
                        current.append(c)
                        if(c==']'){if(next==']'){current.append(next);i+=2;continue};bracket=false}
                        if(c=='\n')processLineEnd() else if(!c.isWhitespace())onlyWhitespaceOnLine=false
                        i++;continue
                    }
                    if(backtick){
                        current.append(c)
                        if(c=='`'){if(next=='`'){current.append(next);i+=2;continue};backtick=false}
                        if(c=='\n')processLineEnd() else if(!c.isWhitespace())onlyWhitespaceOnLine=false
                        i++;continue
                    }

                    if(atLineStart){
                        if(c==' '||c=='\t'||c=='\r'){current.append(c);if(linePrefix.length<256)linePrefix.append(c);i++;continue}
                        if(linePrefix.isEmpty())lineStartIndex=current.length
                        atLineStart=false
                    }
                    if(linePrefix.length<256)linePrefix.append(c)
                    if(c=='-'&&next=='-'){
                        current.append(c).append(next);lineComment=true;i+=2;continue
                    }
                    if(c=='#'&&(i==0||buffer[i-1].isWhitespace())){current.append(c);lineComment=true;i++;continue}
                    if(c=='/'&&next=='*'){current.append(c).append(next);blockComment=true;i+=2;continue}
                    if(c=='\''||c=='"'){current.append(c);quote=c;i++;continue}
                    if(c=='`'){current.append(c);backtick=true;i++;continue}
                    if(c=='['){current.append(c);bracket=true;i++;continue}
                    if(c=='(')parenDepth++ else if(c==')'&&parenDepth>0)parenDepth--
                    current.append(c)
                    if(delimiter==";"&&c==';'&&parenDepth==0){flush();i++;continue}
                    if(delimiter!=";"&&parenDepth==0&&suffixMatches(delimiter)){current.setLength(current.length-delimiter.length);flush();i++;continue}
                    if(c=='\n')processLineEnd() else if(!c.isWhitespace())onlyWhitespaceOnLine=false
                    i++
                }
            }
            if(current.isNotBlank())flush()
        }
    }
    private data class TabularSample(val headers:List<String>, val rows:List<List<String>>)
    private fun safeTableName(fileName:String):String{
        val base=fileName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_]+"),"_").trim('_').ifBlank{"ImportedData"}
        return if(base.firstOrNull()?.isDigit()==true) "T_$base" else base
    }
    private fun inferTabularType(values:List<String>):String{
        val nonBlank=values.filter{it.isNotBlank()}
        if(nonBlank.isEmpty())return "TEXT"
        if(nonBlank.all{it.matches(Regex("[-+]?\\d+"))})return "INTEGER"
        if(nonBlank.all{it.matches(Regex("[-+]?(?:\\d+\\.\\d+|\\d+\\.?)"))})return "REAL"
        return "TEXT"
    }
    private fun parseDelimitedRecord(reader:Reader, delimiter:Char):String?{
        val out=StringBuilder(256);var inQuotes=false;var sawAny=false
        while(true){
            val code=reader.read()
            if(code<0)return if(sawAny)out.toString() else null
            sawAny=true;val ch=code.toChar()
            if(ch=='"'){
                out.append(ch)
                if(inQuotes){
                    reader.mark(1)
                    val next=reader.read()
                    if(next=='"'.code){out.append(next.toChar())}else{if(next>=0)reader.reset();inQuotes=false}
                }else inQuotes=true
            }else if((ch=='\n'||ch=='\r')&&!inQuotes){
                if(ch=='\r'){reader.mark(1);val next=reader.read();if(next!='\n'.code&&next>=0)reader.reset()}
                return out.toString()
            }else out.append(ch)
        }
    }
    private fun parseCsvLine(line:String, delimiter:Char):List<String>{
        val out=mutableListOf<String>();val cur=StringBuilder();var quoted=false;var i=0
        while(i<line.length){
            val ch=line[i]
            if(ch=='"'){
                if(quoted&&i+1<line.length&&line[i+1]=='"'){cur.append('"');i+=2;continue}
                quoted=!quoted
            }else if(ch==delimiter&&!quoted){out.add(cur.toString());cur.setLength(0)}else cur.append(ch)
            i++
        }
        out.add(cur.toString());return out
    }
    private fun readDelimitedSample(uri:Uri,delimiter:Char):TabularSample{
        contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use{r->
            val first=parseDelimitedRecord(r,delimiter) ?: throw Exception("The file is empty")
            val headers0=parseCsvLine(first,delimiter).mapIndexed{idx,v->v.trim().ifBlank{"column_${idx+1}"}}
            val headers=headers0.mapIndexed{idx,h->if(headers0.indexOf(h)==idx)h else "${h}_$idx"}
            val rows=mutableListOf<List<String>>()
            while(rows.size<1000){checkImportCancelled();val record=parseDelimitedRecord(r,delimiter)?:break;if(record.isBlank())continue;rows.add(parseCsvLine(record,delimiter))}
            if(headers.isEmpty())throw Exception("No columns were found")
            return TabularSample(headers,rows)
        } ?: throw Exception("Could not read input file")
    }
    private fun importDelimitedStream(uri:Uri,fileName:String,delimiter:Char):ImportOutcome{
        val sample=readDelimitedSample(uri,delimiter);val target=File(filesDir,"Imported_${System.currentTimeMillis()}_${fileName.replace(Regex("[^A-Za-z0-9._-]"),"_")}.db")
        var fresh:SQLiteDatabase?=null;var rows=0L;var skipped=0L;var inTx=false
        try{
            fresh=SQLiteDatabase.openOrCreateDatabase(target,null);configureDatabase(fresh)
            val table=safeTableName(fileName);val defs=sample.headers.mapIndexed{i,h->"\"${h.replace("\"","\"\"")}\" ${inferTabularType(sample.rows.mapNotNull{it.getOrNull(i)})}"}.joinToString(", ")
            fresh.execSQL("CREATE TABLE \"${table.replace("\"","\"\"")}\" ($defs)")
            val placeholders=sample.headers.joinToString(", "){"?"};val sql="INSERT INTO \"${table.replace("\"","\"\"")}\" (${sample.headers.joinToString(", "){"\"${it.replace("\"","\"\"")}\""}}) VALUES ($placeholders)"
            fresh.beginTransaction();inTx=true
            val statement=fresh.compileStatement(sql);var batch=0
            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use{r->
                parseDelimitedRecord(r,delimiter) // header
                while(true){checkImportCancelled();val record=parseDelimitedRecord(r,delimiter)?:break;if(record.isBlank())continue
                    val vals=parseCsvLine(record,delimiter)
                    if(vals.size>sample.headers.size){skipped++;continue}
                    for(i in sample.headers.indices){if(i<vals.size)statement.bindString(i+1,vals[i]) else statement.bindNull(i+1)}
                    statement.executeInsert();rows++;batch++
                    if(batch>=5000){fresh.setTransactionSuccessful();fresh.endTransaction();inTx=false;fresh.beginTransaction();inTx=true;batch=0}
                    if(rows%5000L==0L)runOnUiThread{if(importInProgress)importDetailView?.text="$rows rows processed"}
                }
            } ?: throw Exception("Could not reopen $fileName")
            checkImportCancelled();if(inTx){fresh.setTransactionSuccessful();fresh.endTransaction();inTx=false}
            return ImportOutcome(fresh,target,table,"Imported $fileName · ${humanBytes(target.length())} · $rows rows"+(if(skipped>0)" · skipped $skipped malformed row(s)" else "")).also{fresh=null}
        }catch(e:Exception){try{if(inTx)fresh?.endTransaction()}catch(_:Exception){};try{fresh?.close()}catch(_:Exception){};target.delete();throw e}
    }
    private fun jsonScalar(reader:JsonReader):String{
        return when(reader.peek()){
            JsonToken.NULL->{reader.nextNull();""}
            JsonToken.BOOLEAN->reader.nextBoolean().toString()
            JsonToken.NUMBER->reader.nextString()
            JsonToken.STRING->reader.nextString()
            else->{reader.skipValue();""}
        }
    }
    private fun readJsonObject(reader:JsonReader):LinkedHashMap<String,String>{
        val map=LinkedHashMap<String,String>();reader.beginObject()
        while(reader.hasNext()){val key=reader.nextName();map[key]=jsonScalar(reader)}
        reader.endObject();return map
    }
    private fun openJsonReader(uri:Uri):JsonReader{
        val input=contentResolver.openInputStream(uri)?:throw Exception("Could not read JSON file")
        return JsonReader(InputStreamReader(input,Charsets.UTF_8)).apply{isLenient=true}
    }
    private fun importJsonStream(uri:Uri,fileName:String):ImportOutcome{
        val firstNonSpace=contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use{r->
            var c:Int;do{c=r.read()}while(c>=0&&c.toChar().isWhitespace());c
        } ?: throw Exception("Could not read $fileName")
        val array=firstNonSpace== '['.code
        val sample=LinkedHashMap<String,MutableList<String>>()
        runCatching{openJsonReader(uri).use{jr->if(array)jr.beginArray();var n=0;while((array&&jr.peek()!=JsonToken.END_ARRAY)||(!array&&jr.peek()!=JsonToken.END_DOCUMENT)){val obj=readJsonObject(jr);for((k,v) in obj){sample.getOrPut(k){mutableListOf()}.let{if(it.size<100)it.add(v)}};n++;if(n>=1000)break;if(!array&&jr.peek()==JsonToken.END_DOCUMENT)break};if(array)jr.endArray()}}.getOrElse{throw Exception("Invalid JSON/NDJSON: ${it.message?:("parse error")}")}
        if(sample.isEmpty())throw Exception("No JSON objects were found")
        val headers=sample.keys.toList();val target=File(filesDir,"Imported_${System.currentTimeMillis()}_${fileName.replace(Regex("[^A-Za-z0-9._-]"),"_")}.db");var fresh:SQLiteDatabase?=null;var rows=0L;var inTx=false
        try{
            fresh=SQLiteDatabase.openOrCreateDatabase(target,null);configureDatabase(fresh);val table=safeTableName(fileName)
            val defs=headers.map{h->"\"${h.replace("\"","\"\"")}\" ${inferTabularType(sample[h].orEmpty())}"}.joinToString(", ");fresh.execSQL("CREATE TABLE \"${table.replace("\"","\"\"")}\" ($defs)")
            val sql="INSERT INTO \"${table.replace("\"","\"\"")}\" (${headers.joinToString(", "){"\"${it.replace("\"","\"\"")}\""}}) VALUES (${headers.joinToString(", "){"?"}})";val st=fresh.compileStatement(sql)
            fresh.beginTransaction();inTx=true
            openJsonReader(uri).use{jr->
                if(array)jr.beginArray()
                while((array&&jr.peek()!=JsonToken.END_ARRAY)||(!array&&jr.peek()!=JsonToken.END_DOCUMENT)){
                    checkImportCancelled()
                    val obj=readJsonObject(jr)
                    for((i,h) in headers.withIndex()){
                        val v=obj[h]
                        if(v==null)st.bindNull(i+1) else st.bindString(i+1,v)
                    }
                    st.executeInsert();rows++
                    if(rows%5000L==0L){
                        fresh!!.setTransactionSuccessful();fresh!!.endTransaction();inTx=false;fresh!!.beginTransaction();inTx=true
                        runOnUiThread{if(importInProgress)importDetailView?.text="$rows JSON rows processed"}
                    }
                }
                if(array)jr.endArray()
            }
            checkImportCancelled();if(inTx){fresh.setTransactionSuccessful();fresh.endTransaction();inTx=false}
            return ImportOutcome(fresh,target,table,"Imported $fileName · ${humanBytes(target.length())} · $rows JSON rows").also{fresh=null}
        }catch(e:Exception){try{if(inTx)fresh?.endTransaction()}catch(_:Exception){};try{fresh?.close()}catch(_:Exception){};target.delete();throw e}
    }
    private fun humanBytes(bytes:Long):String{
        if(bytes<1024)return "$bytes B";if(bytes<1024*1024)return "${bytes/1024} KB";if(bytes<1024*1024*1024)return String.format(Locale.US,"%.1f MB",bytes/1024.0/1024.0);return String.format(Locale.US,"%.2f GB",bytes/1024.0/1024.0/1024.0)
    }
    private fun isQueryStatement(s:String)=s.trimStart().let{it.startsWith("SELECT",true)||it.startsWith("WITH",true)||it.startsWith("PRAGMA",true)||it.startsWith("EXPLAIN",true)||it.startsWith("SHOW",true)||it.startsWith("DESCRIBE",true)||it.startsWith("DESC",true)}
    private fun leadingKeyword(s:String)=Regex("^\\s*([A-Za-z]+)").find(s)?.groupValues?.get(1)?.uppercase(Locale.US)?: "SQL"
    private fun splitSql(s:String):List<String>{
        val out=mutableListOf<String>();val current=StringBuilder();var quote:Char?=null;var bracket=false;var backtick=false;var lineComment=false;var blockComment=false;var i=0
        fun emit(){val part=current.toString().trim();if(part.isNotBlank())out.add(part);current.setLength(0)}
        while(i<s.length){
            val c=s[i];val next=if(i+1<s.length)s[i+1] else '\u0000'
            if(lineComment){current.append(c);if(c=='\n')lineComment=false;i++;continue}
            if(blockComment){current.append(c);if(c=='*'&&next=='/'){current.append(next);i+=2;blockComment=false}else i++;continue}
            if(quote!=null){
                current.append(c)
                if(c=='\\'&&next!='\u0000'){current.append(next);i+=2;continue}
                if(c==quote){
                    if(next==quote){current.append(next);i+=2;continue}
                    quote=null
                }
                i++;continue
            }
            if(bracket){current.append(c);if(c==']'){if(next==']'){current.append(next);i+=2;continue};bracket=false};i++;continue}
            if(backtick){current.append(c);if(c=='`'){if(next=='`'){current.append(next);i+=2;continue};backtick=false};i++;continue}
            if(c=='-'&&next=='-'){current.append(c).append(next);i+=2;lineComment=true;continue}
            if(c=='/'&&next=='*'){current.append(c).append(next);i+=2;blockComment=true;continue}
            if(c=='\''){current.append(c);quote='\'';i++;continue}
            if(c=='"'){current.append(c);quote='\"';i++;continue}
            if(c=='`'){current.append(c);backtick=true;i++;continue}
            if(c=='['){current.append(c);bracket=true;i++;continue}
            if(c==';'){current.append(c);emit();i++;continue}
            current.append(c);i++
        }
        emit()
        // SQL Server batch separators are handled as standalone lines after regular splitting.
        val batches=mutableListOf<String>();for(part in out){val lines=part.lines();var buf=StringBuilder();for(line in lines){if(Regex("(?i)^\\s*GO(?:\\s+\\d+)?\\s*$").matches(line)){if(buf.isNotBlank()){batches.add(buf.toString().trim());buf=StringBuilder()}}else{buf.append(line).append('\n')}};if(buf.isNotBlank())batches.add(buf.toString().trim())}
        return batches
    }
    private fun stripLineComments(s:String):String{
        val out=StringBuilder();var quote:Char?=null;var bracket=false;var backtick=false;var i=0
        while(i<s.length){val c=s[i];val next=if(i+1<s.length)s[i+1] else '\u0000'
            if(quote!=null){out.append(c);if(c==quote){if(next==quote){out.append(next);i+=2;continue};quote=null};i++;continue}
            if(bracket){out.append(c);if(c==']'){if(next==']'){out.append(next);i+=2;continue};bracket=false};i++;continue}
            if(backtick){out.append(c);if(c=='`'){if(next=='`'){out.append(next);i+=2;continue};backtick=false};i++;continue}
            if(c=='\''||c=='"'){out.append(c);quote=c;i++;continue};if(c=='`'){out.append(c);backtick=true;i++;continue};if(c=='['){out.append(c);bracket=true;i++;continue}
            if(c=='-'&&next=='-'){while(i<s.length&&s[i]!='\n')i++;continue};if(c=='#'&&(i==0||s[i-1]=='\n')){while(i<s.length&&s[i]!='\n')i++;continue};out.append(c);i++}
        return out.toString()
    }
    private fun mysqlIdentifierToSqlite(sql:String):String{
        var s=sql.trim().removeSuffix(";").trim()
        val leading=stripLeadingSqlComments(s).trim()
        if(leading.matches(Regex("(?is)^SHOW\\s+TABLES\\s*$"))){
            return "SELECT name AS Tables FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata' ORDER BY name"
        }
        if(leading.matches(Regex("(?is)^SHOW\\s+DATABASES\\s*$"))){
            return "SELECT 'Local SQLite' AS \"Database\""
        }
        var m=Regex("(?is)^SHOW\\s+COLUMNS\\s+FROM\\s+(.+)$").find(leading)
        if(m!=null){
            val table=m.groupValues[1].trim().trim('`','"','[',']').substringAfterLast('.').trim('`','"','[',']')
            val safe=table.replace("'","''")
            return "PRAGMA table_info('$safe')"
        }
        m=Regex("(?is)^DESCRIBE\\s+(.+)$").find(leading) ?: Regex("(?is)^DESC\\s+(.+)$").find(leading)
        if(m!=null){
            val table=m.groupValues[1].trim().trim('`','"','[',']').substringAfterLast('.').trim('`','"','[',']')
            val safe=table.replace("'","''")
            return "PRAGMA table_info('$safe')"
        }
        m=Regex("(?is)^SHOW\\s+INDEX(?:ES)?\\s+FROM\\s+(.+)$").find(leading)
        if(m!=null){
            val table=m.groupValues[1].trim().trim('`','"','[',']').substringAfterLast('.').trim('`','"','[',']')
            val safe=table.replace("'","''")
            return "PRAGMA index_list('$safe')"
        }
        m=Regex("(?is)^SHOW\\s+CREATE\\s+TABLE\\s+(.+)$").find(leading)
        if(m!=null){
            val table=m.groupValues[1].trim().trim('`','"','[',']').substringAfterLast('.').trim('`','"','[',']')
            return "SELECT name, sql FROM sqlite_master WHERE type='table' AND name='${table.replace("'","''")}'"
        }
        // MySQL uses LIMIT offset, count; SQLite uses LIMIT count OFFSET offset.
        s=replaceOutsideSingleQuotes(s, Regex("(?i)\\bLIMIT\\s+(\\d+)\\s*,\\s*(\\d+)")){m2->"LIMIT ${m2.groupValues[2]} OFFSET ${m2.groupValues[1]}"}
        // Common MySQL date helpers that have direct SQLite equivalents.
        s=replaceOutsideSingleQuotes(s, Regex("(?i)\\bNOW\\s*\\(\\s*\\)")){"CURRENT_TIMESTAMP"}
        s=replaceOutsideSingleQuotes(s, Regex("(?i)\\bCURDATE\\s*\\(\\s*\\)")){"CURRENT_DATE"}
        s=replaceOutsideSingleQuotes(s, Regex("(?i)\\bCURTIME\\s*\\(\\s*\\)")){"CURRENT_TIME"}
        s=replaceOutsideSingleQuotes(s, Regex("(?i)\\bIF\\s*\\(([^(),]+),([^(),]+),([^()]+)\\)")){m2->"CASE WHEN ${m2.groupValues[1]} THEN ${m2.groupValues[2]} ELSE ${m2.groupValues[3]} END"}
        s=replaceOutsideSingleQuotes(s, Regex("(?is)MATCH\\s*\\(([^)]*)\\)\\s+AGAINST\\s*\\(\\s*'([^']*)'[^)]*\\)")){m2->
            val columns=m2.groupValues[1].split(',').map{it.trim()}.filter{it.isNotBlank()}
            val terms=m2.groupValues[2].replace("+"," ").replace("-"," ").split(Regex("\\s+")).map{it.trim()}.filter{it.isNotBlank()}
            if(columns.isEmpty()||terms.isEmpty())"1=1" else terms.joinToString(" AND "){term->"("+columns.joinToString(" OR "){col->"$col LIKE '%${term.replace("'","''")}%"+"'"}+ ")"}
        }
        s=replaceOutsideSingleQuotes(s, Regex("(?is)DATE_FORMAT\\s*\\(\\s*([^,]+?)\\s*,\\s*'([^']+)'\\s*\\)")){m2->
            val fmt=m2.groupValues[2].replace("%i","%M").replace("%s","%S")
            "strftime('$fmt', ${m2.groupValues[1].trim()})"
        }
        s=replaceOutsideSingleQuotes(s, Regex("(?is)CONCAT\\s*\\(\\s*([^,()]+)\\s*,\\s*([^,()]+)\\s*\\)")){m2->"(${m2.groupValues[1].trim()} || ${m2.groupValues[2].trim()})"}
        // Imported databases are flattened into one local SQLite schema, so remove
        s=replaceOutsideSingleQuotes(s, Regex("`([^`]+)`")){m2->"\"${m2.groupValues[1].replace("\"","\"\"")}\""}
        // MySQL's INSERT IGNORE maps directly to SQLite's conflict behavior.
        s=s.replace(Regex("(?i)^INSERT\\s+IGNORE\\s+INTO"), "INSERT OR IGNORE INTO")
        return s.trim()
    }
    private fun runCompatibleSql(sql:String):String{
        val cleaned=stripLeadingSqlComments(sql).trim().removeSuffix(";").trim()
        if(cleaned.matches(Regex("(?is)^(USE|SET|LOCK\\s+TABLES|UNLOCK\\s+TABLES|DELIMITER)\\b.*$"))) return ""
        return mysqlIdentifierToSqlite(sql)
    }
    private data class StatementSpan(val sql:String, val number:Int, val start:Int, val end:Int)

    private fun scanStatementSpans(text:String):List<StatementSpan>{
        val out=mutableListOf<StatementSpan>();var start=0;var statementNumber=1;var quote:Char?=null;var bracket=false;var backtick=false;var lineComment=false;var blockComment=false;var i=0
        fun emit(endExclusive:Int){
            val raw=text.substring(start,endExclusive)
            if(stripLineComments(raw).isNotBlank()) out.add(StatementSpan(raw.trim(),statementNumber,start,endExclusive))
            statementNumber++
            start=endExclusive
        }
        while(i<text.length){
            val c=text[i];val next=if(i+1<text.length)text[i+1] else '\u0000'
            if(lineComment){if(c=='\n')lineComment=false;i++;continue}
            if(blockComment){if(c=='*'&&next=='/'){blockComment=false;i+=2}else i++;continue}
            if(quote!=null){if(c==quote){if(next==quote){i+=2;continue};quote=null};i++;continue}
            if(bracket){if(c==']'){if(next==']'){i+=2;continue};bracket=false};i++;continue}
            if(backtick){if(c=='`'){if(next=='`'){i+=2;continue};backtick=false};i++;continue}
            if(c=='-'&&next=='-'){lineComment=true;i+=2;continue}
            if(c=='/'&&next=='*'){blockComment=true;i+=2;continue}
            if(c=='\''||c=='"'){quote=c;i++;continue}
            if(c=='`'){backtick=true;i++;continue}
            if(c=='['){bracket=true;i++;continue}
            if(c==';'){emit(i+1);i++;continue}
            i++
        }
        if(start<text.length){
            val raw=text.substring(start)
            if(stripLineComments(raw).isNotBlank()) out.add(StatementSpan(raw.trim(),statementNumber,start,text.length))
        }
        return out
    }

    private fun currentStatementSpanAtCursor():StatementSpan?{
        val text=editor.text.toString();if(text.isBlank())return null
        val cursor=editor.selectionStart.coerceIn(0,text.length)
        return scanStatementSpans(text).lastOrNull{ cursor>=it.start && cursor<=it.end }
            ?: scanStatementSpans(text).lastOrNull{it.start<=cursor}
    }

    private fun currentStatementAtCursor():String? = currentStatementSpanAtCursor()?.sql
    private fun currentStatementNumberAtCursor():Int? = currentStatementSpanAtCursor()?.number
    private fun updateCurrentStatementIndicator(){}
    private fun animateContentSwap(view:View){view.animate().cancel();view.alpha=.82f;view.translationY=dp(2).toFloat();view.animate().alpha(1f).translationY(0f).setDuration(150).setInterpolator(android.view.animation.DecelerateInterpolator()).start()}
    internal fun runSelectedOrAll(currentOnly:Boolean=false){
        if(queryRunning){queryCancelled=true;activeQueryCancellation?.cancel();status.text="Stopping query…";return}
        val fullSource=editor.text.toString()
        val a=editor.selectionStart;val z=editor.selectionEnd
        val selStart=minOf(a,z);val selEnd=maxOf(a,z)
        val selected=if(a!=z)fullSource.substring(selStart,selEnd).trim() else ""
        val currentSpan=if(currentOnly)currentStatementSpanAtCursor() else null
        val source=when{currentOnly->currentSpan?.sql.orEmpty();selected.isNotEmpty()->selected;else->fullSource}
        val statements=splitSql(source).filter{stripLineComments(it).isNotBlank()}
        if(statements.isEmpty()){showError(if(currentOnly)"No SQL statement at the cursor" else "Nothing to run");return}
        val startSearch=when{currentOnly->currentSpan?.start?:0;selected.isNotEmpty()->selStart;else->0}
        val sourceSpans=scanStatementSpans(fullSource)
        var searchCursor=startSearch.coerceAtLeast(0)
        val statementNumbers=statements.mapIndexed{idx,statement->
            if(currentOnly) currentSpan?.number ?: (idx+1)
            else {
                val needle=statement.trim().removeSuffix(";").trim()
                val found=if(needle.isBlank())-1 else fullSource.indexOf(needle, searchCursor)
                val span=if(found>=0) sourceSpans.firstOrNull{found>=it.start && found<it.end} else null
                if(found>=0) searchCursor=(found+needle.length).coerceAtLeast(searchCursor)
                span?.number ?: (idx+1)
            }
        }
        val executionDb=if(::db.isInitialized&&db.isOpen)db else {showError("No active database");return}
        // Match MySQL Workbench-style result lifecycle: each new execution replaces
        // prior unpinned results, while explicitly pinned results remain visible.
        removeUnpinnedResults()
        queryRunning=true;queryCancelled=false;val executionStarted=System.nanoTime();val cancellation=CancellationSignal();activeQueryCancellation=cancellation;status.text=if(currentOnly)"Running current statement…" else "Running ${statements.size} statement(s)…";status.setTextColor(Color.rgb(170,165,178))
        Thread{var ok=0;var err=0;var schemaChanged=false
            for((idx,s) in statements.withIndex()){if(queryCancelled)break;val statementNumber=statementNumbers.getOrElse(idx){idx+1};val label=leadingKeyword(s)+" "+statementNumber+(if(currentOnly)" · current" else if(selected.isNotEmpty())" · selected" else "");val started=System.nanoTime();try{
                val ruleViolation=MySqlStrictRuleValidator.validate(executionDb,s)
                if(ruleViolation!=null){
                    val msg="MySQL ${ruleViolation.code}: ${ruleViolation.message}"
                    val location=lineColumnFor(editor.text.toString(),s)
                    runOnUiThread{if(!queryCancelled)addQueryError(statementNumber,msg,location,elapsed(started),s)}
                    err++
                    continue
                }
                val compatible=runCompatibleSql(s)
                if(compatible.isBlank() && s.trimStart().matches(Regex("(?is)^(USE|SET|LOCK\\s+TABLES|UNLOCK\\s+TABLES|DELIMITER)\\b.*"))){
                    runOnUiThread{if(!queryCancelled)addResultTab("${leadingKeyword(s)} ${statementNumber} · OK",buildMessageView("✓ MySQL session/dump directive accepted for the local SQLite workspace\n\nExecution: ${elapsed(started)} ms"),false,null,"${leadingKeyword(s)} ${statementNumber} · OK",query=s)}
                } else if(isQueryStatement(s)){
                    if(compatible.trimStart().startsWith("EXPLAIN QUERY PLAN",true)){val rows=planRows(compatible);val csv=rows.map{listOf(it.first.toString(),it.second.toString(),it.third)};runOnUiThread{if(!queryCancelled)addResultTab("Plan ${statementNumber}",buildPlanView(rows),false,csv,"Plan ${statementNumber}",query=s)}}
                    else{executionDb.rawQuery(compatible,null,cancellation).use{c->val data=readCursorRows(c);val tableLabel=if(leadingKeyword(s).equals("SELECT",true))ResultLabelResolver.tableLabel(s)else null;val resolvedLabel=tableLabel?:nextGenericResultLabel();runOnUiThread{if(!queryCancelled)addResultTab(resolvedLabel,buildGrid(data.first,data.third),false,data.first,resolvedLabel,query=s)}}}
                }
                else{executionDb.execSQL(compatible);if(label.startsWith("CREATE",true)||label.startsWith("DROP",true)||label.startsWith("ALTER",true))schemaChanged=true;val changed=executionDb.rawQuery("SELECT changes()",null).use{c->c.moveToFirst();c.getInt(0)};val message="✓ OK — $changed row(s) affected\n\nExecution: ${elapsed(started)} ms";runOnUiThread{if(!queryCancelled)addResultTab("${leadingKeyword(s)} ${statementNumber} · ${changed} changed",buildMessageView(message),false,null,"${leadingKeyword(s)} ${statementNumber} · ${changed} changed",query=s)}};ok++;history.add(0,s.trim());trimHistory()
            }catch(e:Exception){if(queryCancelled)break;val msg=e.message?:"SQL error";val location=lineColumnFor(editor.text.toString(),s);val elapsedMs=elapsed(started);val statementNumber=statementNumbers.getOrElse(idx){idx+1};runOnUiThread{if(!queryCancelled)addQueryError(statementNumber,msg,location,elapsedMs,s)};err++}}
            runOnUiThread{queryRunning=false;activeQueryCancellation=null;if(queryCancelled){queryCancelled=false;status.text="Query stopped";status.setTextColor(Color.rgb(190,185,195))}else{while(tabs.count{!it.pinned}>30){val oldestUnpinned=tabs.indexOfLast{!it.pinned};if(oldestUnpinned<0)break;removeResultTab(oldestUnpinned)};if(err>0){showPlaceholder();status.text="Query failed · $err error${if(err==1)"" else "s"} in Messages";status.setTextColor(Color.rgb(170,165,178))}else if(tabs.isNotEmpty())selectTab(0)else showPlaceholder();savePrefs();if(err==0){status.text="✓ Ran $ok statement(s) • ${elapsed(executionStarted)} ms"+when{currentOnly->" • current statement";selected.isNotEmpty()->" • selected only";else->""};status.setTextColor(green)};if(schemaChanged)refreshSchemaPanel()}}
        }.start()
    }
    private fun lineColumnFor(sql:String,fragment:String):String{
        val idx=sql.indexOf(fragment.trim()).let{if(it<0)0 else it};val line=sql.substring(0,idx).count{it=='\n'}+1;val last=sql.lastIndexOf('\n',idx-1);val col=idx-(if(last<0)-1 else last);return "line $line, column $col"
    }
    private fun elapsed(s:Long)=((System.nanoTime()-s)/1_000_000L).toString(); private fun trimHistory(){while(history.size>30)history.removeAt(history.lastIndex)}
    private fun readCursorRows(c:Cursor):Triple<List<List<String>>,Int,Boolean>{
        val data=mutableListOf<List<String>>();data.add((0 until c.columnCount).map{c.getColumnName(it)})
        var count=0;var truncated=false
        while(count<1000&&c.moveToNext()){data.add((0 until c.columnCount).map{if(c.isNull(it))"NULL" else c.getString(it)});count++}
        if(count==1000&&c.moveToNext())truncated=true
        return Triple(data,count,truncated)
    }
    private fun buildTableView(c:Cursor):Triple<View,Int,List<List<String>>>{val r=readCursorRows(c);return Triple(buildGrid(r.first,r.third),r.second,r.first)}
    private fun buildGrid(data:List<List<String>>,truncated:Boolean=false):View{
        if(data.isEmpty()) return TextView(this).apply{text="No columns";setPadding(dp(16),dp(16),dp(16),dp(16));setTextColor(white)}
        val grid=VirtualResultGridView(
            this, data, truncated,
            { column -> sortActiveResult(column) },
            { _, _, _ -> },
            { value -> copyText(value) },
            { value -> showFullCellValue(value) },
            resultZoom
        ) { resultZoom = it }
        val wrapper=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=ColorDrawable(Color.rgb(15,16,21))}
        wrapper.addView(grid,LinearLayout.LayoutParams(-1,0,1f))
        val rowCount=(data.size-1).coerceAtLeast(0)
        val columnCount=data.firstOrNull()?.size?:0
        val footer=TextView(this).apply{
            text=if(truncated)"Showing first $rowCount rows × $columnCount columns" else "$rowCount rows × $columnCount columns"
            textSize=9.5f;setTextColor(Color.rgb(128,131,140));setPadding(dp(9),dp(4),dp(9),dp(5));setSingleLine(true)
        }
        wrapper.addView(footer,LinearLayout.LayoutParams(-1,dp(24)))
        return wrapper
    }
    private fun showFullCellValue(value:String){
        val scroll=ScrollView(this).apply{setPadding(dp(6),dp(4),dp(6),dp(4))}
        val text=TextView(this).apply{
            this.text=if(value=="NULL")"NULL" else value
            textSize=13f;setTextColor(white);setPadding(dp(10),dp(8),dp(10),dp(8));setTextIsSelectable(true);setTypeface(android.graphics.Typeface.MONOSPACE)
        }
        scroll.addView(text)
        AlertDialog.Builder(this).setTitle("Cell value").setView(scroll).setPositiveButton("Copy"){_,_->copyText(value)}.setNegativeButton("Close",null).show()
    }
    private fun cell(v:String,head:Boolean,onClick:()->Unit):TextView{val t=TextView(this);t.text=v;t.textSize=12f;t.setTextColor(if(head)Color.rgb(185,181,191)else white);t.setPadding(16,12,16,12);t.setBackgroundColor(if(head) Color.rgb(28,31,39) else Color.rgb(24,26,33));if(head)t.setOnClickListener{onClick()} else t.setOnLongClickListener{onClick();true};return t}
    private fun sortActiveResult(column:Int){if(activeTab<0||tabs[activeTab].csv==null)return;val old=tabs[activeTab].csv!!;if(old.size<2)return;val ascending=!(tabs[activeTab].button.tag as? Boolean ?: false);val sorted=old.drop(1).sortedWith(Comparator<List<String>>{a,b->compareCells(a.getOrElse(column){""},b.getOrElse(column){""})}).let{if(ascending)it else it.reversed()};val data=listOf(old[0])+sorted;tabs[activeTab].button.tag=ascending;tabs[activeTab].button.text=tabs[activeTab].title+" · sort ${if(ascending)"↑" else "↓"}";tabs[activeTab]=tabs[activeTab].copy(view=buildGrid(data),csv=data);selectTab(activeTab)}
    private fun compareCells(a:String,b:String):Int{val da=a.toDoubleOrNull();val db=b.toDoubleOrNull();return if(da!=null&&db!=null)da.compareTo(db) else a.compareTo(b,true)}
    private fun copyText(v:String){(getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("SQL cell",v));Toast.makeText(this,"Cell copied",Toast.LENGTH_SHORT).show()}
    private fun nextGenericResultLabel():String { val n=nextResultTabNumber; nextResultTabNumber=n+1; return "Result $n" }

    private fun addResultTab(label:String,content:View,isError:Boolean,csv:List<List<String>>?,title:String,query:String?=null){
        val wrap=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(2),0,dp(1),0);background=rounded(Color.rgb(25,26,29),1,Color.rgb(49,51,56),12f);clipChildren=false;clipToPadding=false}
        val btn=Button(ContextThemeWrapper(this,R.style.FlatButton)).apply{
            text=label;isAllCaps=false;minHeight=0;minWidth=0;setPadding(dp(3),0,0,0);textSize=10f;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setMaxWidth(dp(132));gravity=Gravity.CENTER_VERTICAL;stateListAnimator=null;background=ColorDrawable(Color.TRANSPARENT);contentDescription=label
        }
        val more=TextView(this).apply{
            text="⋯";textSize=13.5f;gravity=Gravity.CENTER;setTextColor(Color.rgb(170,172,178));isClickable=true;isFocusable=true;minHeight=0;minWidth=0;contentDescription="More actions for $label";background=ColorDrawable(Color.TRANSPARENT);setPadding(0,0,0,0)
        }
        val close=Button(ContextThemeWrapper(this,R.style.FlatButton)).apply{
            text="×";isAllCaps=false;minHeight=0;minWidth=0;setPadding(0,0,0,0);textSize=13f;gravity=Gravity.CENTER;contentDescription="Close $label";stateListAnimator=null;background=ColorDrawable(Color.TRANSPARENT)
        }
        wrap.addView(btn,LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,dp(25)));wrap.addView(more,LinearLayout.LayoutParams(dp(16),dp(25)));wrap.addView(close,LinearLayout.LayoutParams(dp(16),dp(25)))
        val lp=LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,dp(27)).apply{marginEnd=dp(3);gravity=Gravity.CENTER_VERTICAL};tabStrip.addView(wrap,0,lp)
        val tab=ResultTab(btn,more,close,wrap,content,isError,csv,title,query=query,pinned=false);tabs.add(0,tab)
        btn.setOnClickListener{val i=tabs.indexOfFirst{it.container===wrap};if(i>=0)selectTab(i)}
        btn.setOnLongClickListener{val i=tabs.indexOfFirst{it.container===wrap};if(i>=0&&!tabs[i].isError)showResultTabMenu(i);true}
        if(android.os.Build.VERSION.SDK_INT>=23)btn.setOnContextClickListener{val i=tabs.indexOfFirst{it.container===wrap};if(i>=0&&!tabs[i].isError)showResultTabMenu(i);true}
        more.setOnClickListener{val i=tabs.indexOfFirst{it.container===wrap};if(i>=0&&!tabs[i].isError)showResultTabMenu(i)}
        close.setOnClickListener{val i=tabs.indexOfFirst{it.container===wrap};if(i>=0)removeResultTab(i)}
    }
    private fun addResultTab(label:String,content:View,isError:Boolean,csv:List<List<String>>?,title:String,errorMessage:String,query:String?=null){
        val before=tabs.size
        addResultTab(label,content,isError,csv,title,query)
        if(tabs.size>before){
            val index=0
            tabs[index]=tabs[index].copy(errorMessage=errorMessage)
        }
    }
    private fun exportActiveResultCsv(){
        if(activeTab<0){showError("Select a result first");return}
        resultExportController.exportActive(activeTab,tabs[activeTab].title)
    }
    private fun showResultTabMenu(index:Int){
        val tab=tabs.getOrNull(index) ?: return
        if(tab.isError)return
        val anchor=tab.moreButton
        val popup=PopupWindow(this)
        val menu=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(4),dp(4),dp(4),dp(4))
            background=rounded(Color.rgb(29,30,36),1,Color.rgb(78,79,87),10f)
            elevation=dp(8).toFloat()
            clipChildren=false
            clipToPadding=false
        }
        fun item(textValue:String, action:()->Unit):View = TextView(this).apply{
            text=textValue
            textSize=11.5f
            setTextColor(Color.rgb(236,235,239))
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(10),0,dp(12),0)
            isClickable=true
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener{popup.dismiss();action()}
            layoutParams=LinearLayout.LayoutParams(-1,dp(32))
            setOnHoverListener{v,e->
                when(e.actionMasked){MotionEvent.ACTION_HOVER_ENTER->v.setBackgroundColor(Color.rgb(52,54,61));MotionEvent.ACTION_HOVER_EXIT->v.setBackgroundColor(Color.TRANSPARENT)}
                false
            }
        }
        menu.addView(item("Export CSV"){resultExportController.exportTab(index,tab.title)})
        menu.addView(item("Save as…"){resultExportController.saveAsTab(index,tab.title)})
        menu.addView(item("Copy table"){
            val data=tab.csv
            if(data.isNullOrEmpty()) Toast.makeText(this,"No table data to copy",Toast.LENGTH_SHORT).show() else {
                val text=data.joinToString("\n"){row->row.joinToString("\t")}
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText(tab.title,text))
                Toast.makeText(this,"Table copied",Toast.LENGTH_SHORT).show()
            }
        })
        menu.addView(item("Copy selected cell"){
            val value=tab.view.findVirtualResultGrids().firstOrNull()?.selectedValue()
            if(value==null) Toast.makeText(this,"Select a cell first",Toast.LENGTH_SHORT).show() else copyText(value)
        })
        menu.addView(item("Copy selected row"){
            val grid=tab.view.findVirtualResultGrids().firstOrNull()
            val row=grid?.selectedRowValues()
            if(row.isNullOrEmpty()) Toast.makeText(this,"Select a row first",Toast.LENGTH_SHORT).show() else {
                copyText(row.joinToString("\t"));Toast.makeText(this,"Row copied",Toast.LENGTH_SHORT).show()
            }
        })
        menu.addView(item("Inspect selected cell"){
            val value=tab.view.findVirtualResultGrids().firstOrNull()?.selectedValue()
            if(value==null) Toast.makeText(this,"Select a cell first",Toast.LENGTH_SHORT).show() else showFullCellValue(value)
        })
        menu.addView(item("Export JSON"){resultExportController.exportJsonTab(index,tab.title)})
        menu.addView(item(if(tab.pinned)"Unpin result" else "Pin result"){toggleResultPin(index)})
        menu.addView(item("Close result"){removeResultTab(index)})
        popup.contentView=menu
        popup.width=dp(148)
        popup.height=WindowManager.LayoutParams.WRAP_CONTENT
        popup.isFocusable=true
        popup.isOutsideTouchable=true
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.setOnDismissListener{ }
        popup.showAsDropDown(anchor, -popup.width + anchor.width, dp(4))
        animatePopupIn(menu)
    }
    private fun toggleResultPin(index:Int){
        val tab=tabs.getOrNull(index) ?: return
        if(tab.isError)return
        tabs[index]=tab.copy(pinned=!tab.pinned)
        tabs[index].button.contentDescription=if(tabs[index].pinned)"${tab.title} (pinned)" else tab.title
        tabs[index].moreButton.contentDescription=if(tabs[index].pinned)"More actions for ${tab.title} (pinned)" else "More actions for ${tab.title}"
    }
    private fun removeUnpinnedResults(){
        if(tabs.isEmpty())return
        val wasActiveTab=tabs.getOrNull(activeTab)
        val keep=tabs.filter{it.pinned}
        if(keep.size==tabs.size)return
        tabs.clear()
        tabStrip.removeAllViews()
        resultContainer.removeAllViews()
        tabs.addAll(keep)
        activeTab=when{
            tabs.isEmpty()->-1
            wasActiveTab?.pinned==true -> tabs.indexOfFirst{it.container===wasActiveTab.container}.coerceAtLeast(0)
            else -> -1
        }
        if(activeTab>=0)selectTab(activeTab) else showPlaceholder()
    }
    private fun removeResultTab(index:Int){
        if(index<0||index>=tabs.size)return
        val wasActive=index==activeTab
        tabStrip.removeView(tabs[index].container);tabs.removeAt(index)
        activeTab=when{tabs.isEmpty()->-1;wasActive->minOf(index,tabs.lastIndex);index<activeTab->activeTab-1;else->activeTab}
        if(activeTab>=0)selectTab(activeTab) else showPlaceholder()
    }
    private fun selectTab(i:Int){
        if(i<0||i>=tabs.size)return
        activeTab=i;resultContainer.removeAllViews();resultContainer.addView(tabs[i].view);animateContentSwap(resultContainer)
        for((j,t) in tabs.withIndex()){
            val selected=j==i
            t.container.background=rounded(if(selected)Color.rgb(52,54,59) else Color.rgb(25,26,29),1,if(selected)Color.rgb(52,54,59) else Color.rgb(49,51,56),12f)
            t.button.setBackgroundColor(Color.TRANSPARENT)
            t.moreButton.setBackgroundColor(Color.TRANSPARENT)
            t.closeButton.setBackgroundColor(Color.TRANSPARENT)
            t.button.setTextColor(if(selected)Color.WHITE else if(t.isError)red else Color.rgb(185,181,191))
            t.moreButton.setTextColor(if(selected)Color.WHITE else Color.rgb(160,162,168))
            t.closeButton.setTextColor(if(selected)Color.WHITE else if(t.isError)red else Color.rgb(151,153,158))
        }
        
    }
    private fun clearResults(){tabStrip.removeAllViews();resultContainer.removeAllViews();tabs.clear();activeTab=-1;nextResultTabNumber=1;}
    private fun setupErrorPanel(){ errorPanelController.setup() }

    private fun addQueryError(statementNumber:Int,message:String,location:String,elapsedMs:String,query:String){
        errorPanelController.addError(statementNumber,message,location,elapsedMs,query)
    }

    private fun setupResizeHandle(){
        val handle=resizeHandle
        handle.setOnTouchListener(object:View.OnTouchListener{
            private var startY=0f
            private var startEditor=0
            private var startResults=0
            private var dragging=false
            override fun onTouch(v:View,event:MotionEvent):Boolean{
                when(event.actionMasked){
                    MotionEvent.ACTION_DOWN->{
                        startY=event.rawY
                        startEditor=editorSurface.height
                        startResults=resultArea.height
                        dragging=true
                        v.parent.requestDisallowInterceptTouchEvent(true)
                        v.alpha=1f
                        return true
                    }
                    MotionEvent.ACTION_MOVE->{
                        if(!dragging)return true
                        val total=(startEditor+startResults).coerceAtLeast(1)
                        val delta=(event.rawY-startY).toInt()
                        val minEditor=dp(120)
                        val minResults=dp(150)
                        val newEditor=(startEditor+delta).coerceIn(minEditor,(total-minResults).coerceAtLeast(minEditor))
                        val newResults=total-newEditor
                        (editorSurface.layoutParams as? LinearLayout.LayoutParams)?.let{lp->lp.height=newEditor;lp.weight=0f;editorSurface.layoutParams=lp}
                        (resultArea.layoutParams as? LinearLayout.LayoutParams)?.let{lp->lp.height=newResults;lp.weight=0f;resultArea.layoutParams=lp}
                        editorSurface.requestLayout();resultArea.requestLayout()
                        return true
                    }
                    MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{
                        dragging=false
                        v.parent.requestDisallowInterceptTouchEvent(false)
                        return true
                    }
                }
                return true
            }
        })
    }
    private fun renderScriptTabs(){
        if(!::scriptTabStrip.isInitialized)return
        scriptTabStrip.removeAllViews()
        scriptTabs.forEachIndexed{index,tab->
            val selected=index==activeScriptTab
            val label=(if(tab.dirty)"● " else "")+tab.name
            val wrap=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(4),0,dp(4),0);background=rounded(if(selected)Color.rgb(52,54,59) else Color.rgb(25,26,29),1,if(selected)Color.rgb(52,54,59) else Color.rgb(49,51,56),12f)}
            val b=TextView(this).apply{
                text=label;setTextColor(if(selected)Color.WHITE else Color.rgb(198,199,202));textSize=11f;gravity=Gravity.CENTER_VERTICAL;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
                setPadding(dp(7),0,dp(4),0);contentDescription="${tab.name}${if(tab.dirty)" unsaved" else ""}";isClickable=true
                setOnClickListener{switchScriptTab(index)}
                setOnLongClickListener{
                    cardListDialog("Query · ${tab.name}",listOf("Rename")){renameScriptTab(index)}; true
                }
            }
            val close=TextView(this).apply{
                text="×";setTextColor(if(selected)Color.WHITE else Color.rgb(151,153,158));textSize=14f;gravity=Gravity.CENTER;setPadding(0,0,0,0);isClickable=true;contentDescription="Close ${tab.name}"
                setOnClickListener{closeScriptTab(index)}
            }
            wrap.addView(b,LinearLayout.LayoutParams.WRAP_CONTENT,dp(26));wrap.addView(close,LinearLayout.LayoutParams(dp(22),dp(26)))
            wrap.setOnLongClickListener{cardListDialog("Query · ${tab.name}",listOf("Rename")){renameScriptTab(index)};true}
            scriptTabStrip.addView(wrap,LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,dp(28)).apply{marginEnd=dp(4)})
        }
        val add=Button(ContextThemeWrapper(this,R.style.FlatButton)).apply{text="+";isAllCaps=false;minHeight=0;minWidth=0;setPadding(0,0,0,0);textSize=15f;contentDescription="New query tab";stateListAnimator=null;setOnClickListener{newScriptTab()}}
        scriptTabStrip.addView(add,LinearLayout.LayoutParams(dp(34),dp(28)))
    }
    private fun setupScriptTabs(){ if(::scriptTabStrip.isInitialized)renderScriptTabs() }
    private fun draftDir():File=File(filesDir,"lily_drafts").apply{if(!exists())mkdirs()}
    private fun restoreScriptTabs(defaultSql:String){
        scriptTabs.clear();var restored=false
        val encoded=prefs.getString("script_tabs_json",null)
        if(!encoded.isNullOrBlank()){
            runCatching{
                val arr=JSONArray(encoded)
                for(i in 0 until arr.length()){
                    val o=arr.getJSONObject(i);val draft=File(draftDir(),"query_$i.sql")
                    val text=if(draft.exists())draft.readText(Charsets.UTF_8) else ""
                    scriptTabs.add(ScriptTab(o.optString("name","Query ${i+1}"),text,o.optInt("selection",0),o.optBoolean("dirty",false)))
                }
                restored=scriptTabs.isNotEmpty()
            }.getOrElse{scriptTabs.clear()}
        }
        if(!restored)scriptTabs.add(ScriptTab("Query 1",defaultSql,defaultSql.length,false))
        activeScriptTab=prefs.getInt("active_script_tab",0).coerceIn(0,scriptTabs.lastIndex)
        switchingScriptTab=true;editor.setText(scriptTabs[activeScriptTab].text);editor.setSelection(scriptTabs[activeScriptTab].selection.coerceIn(0,editor.length()));switchingScriptTab=false
        renderScriptTabs()
    }
    internal fun saveScriptTabs(){
        if(scriptTabs.isEmpty())return
        runCatching{
            val dir=draftDir();val arr=JSONArray()
            scriptTabs.forEachIndexed{i,t->{ File(dir,"query_$i.sql").writeText(t.text,Charsets.UTF_8); arr.put(JSONObject().apply{put("name",t.name);put("selection",t.selection.coerceAtLeast(0));put("dirty",t.dirty)}) }}
            dir.listFiles()?.filter{it.name.startsWith("query_")&&it.name.endsWith(".sql")&&it.name.removePrefix("query_").removeSuffix(".sql").toIntOrNull()?.let{n->n>=scriptTabs.size}==true}?.forEach{it.delete()}
            prefs.edit().putString("script_tabs_json",arr.toString()).putInt("active_script_tab",activeScriptTab.coerceAtLeast(0)).apply()
        }
    }
    internal fun persistCurrentScript(){if(scriptTabs.isNotEmpty()&&activeScriptTab in scriptTabs.indices){scriptTabs[activeScriptTab].text=editor.text.toString();scriptTabs[activeScriptTab].selection=editor.selectionStart.coerceAtLeast(0);saveScriptTabs()}}
    private fun newScriptTab(){persistCurrentScript();val n=(scriptTabs.size+1);scriptTabs.add(ScriptTab("Query $n","",0,false));saveScriptTabs();switchScriptTab(scriptTabs.lastIndex)}
    private fun switchScriptTab(index:Int){if(index !in scriptTabs.indices)return;persistCurrentScript();activeScriptTab=index;switchingScriptTab=true;editor.setText(scriptTabs[index].text);editor.setSelection(scriptTabs[index].selection.coerceIn(0,editor.length()));switchingScriptTab=false;renderScriptTabs();highlight();updateCurrentStatementIndicator();animateContentSwap(editor);editor.requestFocus()}
    private fun closeScriptTab(index:Int){if(scriptTabs.size==1){showWindowToast("Keep at least one query tab");return};val wasActive=index==activeScriptTab;scriptTabs.removeAt(index);if(wasActive)activeScriptTab=index.coerceAtMost(scriptTabs.lastIndex);else if(index<activeScriptTab)activeScriptTab--;switchingScriptTab=true;editor.setText(scriptTabs[activeScriptTab].text);editor.setSelection(scriptTabs[activeScriptTab].selection.coerceIn(0,editor.length()));switchingScriptTab=false;saveScriptTabs();renderScriptTabs();highlight();updateCurrentStatementIndicator();animateContentSwap(editor)}
    private fun renameScriptTab(index:Int){val input=EditText(this).apply{setSingleLine(true);setText(scriptTabs[index].name);setTextColor(white);setHintTextColor(Color.rgb(105,108,119));background=rounded(Color.rgb(28,30,38),1,Color.rgb(54,56,66),9f);setPadding(dp(10),0,dp(10),0)};cardDialog("Rename query",input,"Rename"){val n=input.text.toString().trim();if(n.isNotBlank()){scriptTabs[index].name=n;saveScriptTabs();renderScriptTabs()}}.show()}
    private fun setupWindowDots(){
        windowDotsController = WindowDotsController(
            activity = this,
            preferences = prefs,
            onDot1 = { toggleSidebar() },
            onSaveSql = { persistCurrentScript(); queryFileSaveController.saveActiveQuery() },
            commandProvider = { commandPaletteActions() }
        )
        windowDotsController.setup()
    }
    private fun commandPaletteActions(): List<Pair<String, () -> Unit>> = listOf(
        "Schema Inspector" to {showSchemaInspector()},
        "Table Designer" to {showTableDesignerChooser()},
        "Data Editor" to {showDataEditorChooser()},
        "Query History" to {showHistory()},
        "Saved Snippets" to {showSnippetMenu()},
        "Practice" to {showPractice()},
        "Check Query" to {checkPractice()},
        "Profile Active Query" to {profileActiveQuery()},
        "Index Advisor" to {showIndexAdvisor()},
        "Go to Definition" to {goToDefinition()},
        "Search Active Result" to {searchActiveResult()},
        "Find & Replace" to {showFindReplace()},
        "Go to Line" to {showGoToLine()},
        "Comment / Uncomment" to {toggleComment()},
        "Export Database" to {exportDatabase()},
        "Export Database as SQL" to {exportDatabaseSql()},
        "Optimize Database" to {optimizeDatabase()},
        "Database Performance" to {databasePerformanceInfo()},
        "Database Integrity Check" to {checkDatabaseIntegrity()},
        "Rename Query Tab" to {if(scriptTabs.isNotEmpty())renameScriptTab(activeScriptTab)}
    )
    private fun toggleSidebar(){
        val panel=sidebarPanel ?: return
        sidebarOpen=!sidebarOpen
        if(sidebarOpen){
            panel.visibility=View.VISIBLE;panel.alpha=0f;panel.translationX=-dp(18).toFloat();panel.animate().alpha(1f).translationX(0f).setDuration(180).start()
        }else{
            panel.animate().alpha(0f).translationX(-dp(18).toFloat()).setDuration(160).withEndAction{panel.visibility=View.GONE}.start()
        }
        sidebarDivider?.visibility=View.VISIBLE
        updateDotStates()
    }
    private fun toggleFocusMode(){
        focusMode=!focusMode
        val ids=listOf(R.id.workspaceLabel,R.id.workspaceHint)
        ids.forEach{id->findViewById<View>(id)?.visibility=if(focusMode)View.GONE else View.VISIBLE}
        if(sidebarPanel!=null){
            if(focusMode){focusSidebarWasOpen=sidebarOpen;if(sidebarOpen)toggleSidebar()}
            else if(focusSidebarWasOpen&&!sidebarOpen)toggleSidebar()
        }
        updateDotStates()
        showWindowToast(if(focusMode)"Focus mode on" else "Focus mode off")
    }
    private fun updateDotStates(){ windowDotsController.updateState() }
    private fun showWindowToast(text:String){Toast.makeText(this,text,Toast.LENGTH_SHORT).show()}
    private fun showPlaceholder(){;val t=TextView(this);t.text="Run a query to see results here";t.setTextColor(Color.rgb(104,108,120));t.textSize=13f;t.gravity=Gravity.CENTER;resultContainer.removeAllViews();resultContainer.addView(t)}
    private fun buildMessageView(msg:String):View{val t=TextView(this);t.text=msg;t.textSize=13f;t.setTextColor(Color.rgb(185,181,191));t.setPadding(20,20,20,20);val s=ScrollView(this);s.addView(t);return s}
    private fun buildErrorView(message:String,location:String,executionMs:String,sql:String):View{
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(16),dp(18),dp(20))}
        val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val icon=TextView(this).apply{text="✕";textSize=24f;setTextColor(red);setPadding(0,0,dp(10),0)}
        val title=TextView(this).apply{text="Query failed";textSize=17f;setTypeface(null,android.graphics.Typeface.BOLD);setTextColor(white)}
        header.addView(icon,LinearLayout.LayoutParams(dp(34),dp(40)));header.addView(title,LinearLayout.LayoutParams(0,dp(40),1f))
        root.addView(header)
        val rule=View(this).apply{setBackgroundColor(Color.rgb(48,50,60))};root.addView(rule,LinearLayout.LayoutParams(-1,dp(1)).apply{topMargin=dp(8);bottomMargin=dp(14)})
        val label=TextView(this).apply{text="SQLITE_ERROR";textSize=11f;setTypeface(null,android.graphics.Typeface.BOLD);setTextColor(red)};root.addView(label)
        val error=TextView(this).apply{text=message;textSize=15f;setTextColor(white);setPadding(0,dp(7),0,dp(14));setTextIsSelectable(true)};root.addView(error)
        val meta=TextView(this).apply{text="$location\nExecution: $executionMs ms";textSize=12f;setTextColor(Color.rgb(155,151,164));setPadding(0,0,0,dp(14))};root.addView(meta)
        val qLabel=TextView(this).apply{text="STATEMENT";textSize=10f;setTypeface(null,android.graphics.Typeface.BOLD);setTextColor(Color.rgb(150,145,158))};root.addView(qLabel)
        val q=TextView(this).apply{text=sql.trim();textSize=12f;typeface=android.graphics.Typeface.MONOSPACE;setTextColor(Color.rgb(205,201,210));setPadding(dp(12),dp(10),dp(12),dp(10));background=rounded(Color.rgb(24,26,33),1,Color.rgb(45,47,57),8f);setTextIsSelectable(true);maxLines=8;ellipsize=android.text.TextUtils.TruncateAt.END};root.addView(q,LinearLayout.LayoutParams(-1,LinearLayout.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(6)})
        val scroll=ScrollView(this);scroll.addView(root);return scroll
    }
    private fun schemaText():String{val b=StringBuilder();db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata' ORDER BY name",null).use{c->if(!c.moveToFirst())b.append("No tables yet.\nImport a .db or .sql file,\nor run a CREATE TABLE.")else{do{val n=c.getString(0);b.append(n).append("  ");try{db.rawQuery("SELECT COUNT(*) FROM \""+n.replace("\"","\"\"")+"\"",null).use{r->if(r.moveToFirst())b.append("(").append(r.getLong(0)).append(" rows)")}}catch(_:Exception){};b.append("\n");db.rawQuery("PRAGMA table_info('"+n.replace("'","''")+"')",null).use{p->while(p.moveToNext())b.append("   ").append(p.getString(1)).append("  ").append(p.getString(2).ifBlank{"ANY"}).append("\n")};b.append("\n")}while(c.moveToNext())}};return b.toString().trimEnd()}
    private fun interactiveSchemaView():View{
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(6),dp(4),dp(6),dp(10))}
        val search=EditText(this).apply{hint="Search databases, tables, columns…";setSingleLine(true);setTextColor(white);setHintTextColor(Color.rgb(100,104,116));textSize=11f;background=rounded(Color.rgb(24,26,33),1,Color.rgb(45,47,57),9f);setPadding(dp(10),0,dp(10),0)}
        root.addView(search,LinearLayout.LayoutParams(-1,dp(38)).apply{bottomMargin=dp(6)})
        root.addView(View(this).apply{setBackgroundColor(Color.rgb(42,44,53))},LinearLayout.LayoutParams(-1,dp(1)).apply{bottomMargin=dp(4)})
        root.addView(TextView(this).apply{text="DATABASES";textSize=9f;letterSpacing=.14f;setTextColor(Color.rgb(112,116,129));setTypeface(null,android.graphics.Typeface.BOLD);setPadding(dp(8),dp(6),dp(8),dp(5))})
        databaseTreePaths().forEach{(label,path)->addDatabaseTreeNode(root,label,path,label=="Parks & Recreation")}
        fun filterView(v:View,q:String):Boolean{val tag=v.tag?.toString();val own=tag?.startsWith("schema:")==true&&tag.removePrefix("schema:").contains(q,true);if(v is ViewGroup){var child=false;for(i in 0 until v.childCount)child=filterView(v.getChildAt(i),q)||child;if(tag?.startsWith("schema:")==true)v.visibility=if(q.isBlank()||own||child)View.VISIBLE else View.GONE;return own||child};if(tag?.startsWith("schema:")==true)v.visibility=if(q.isBlank()||own)View.VISIBLE else View.GONE;return own}
        search.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,st:Int,c:Int,a:Int){};override fun onTextChanged(s:CharSequence?,st:Int,b:Int,c:Int){filterView(root,s?.toString()?.trim().orEmpty())};override fun afterTextChanged(e:Editable?) {}})
        return root
    }
    private fun databaseTreePaths():List<Pair<String,String>>{val out=mutableListOf<Pair<String,String>>();out.add("Parks & Recreation" to ensureSamplePath("Parks & Recreation"));userDatabasePaths().forEach{out.add(databaseLabel(it) to it)};return out}
    private data class ExplorerColumn(val name:String,val type:String,val primaryKey:Boolean)
    private data class ExplorerTable(val name:String,val rowCount:String,val columns:List<ExplorerColumn>)
    private fun loadExplorerTables(path:String):List<ExplorerTable>{
        fun read(database:SQLiteDatabase):List<ExplorerTable>{
            val names=mutableListOf<String>()
            database.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata' ORDER BY name",null).use{c->while(c.moveToNext())names.add(c.getString(0))}
            return names.map{table->
                val columns=mutableListOf<ExplorerColumn>()
                runCatching{database.rawQuery("PRAGMA table_info('${table.replace("'","''")}')",null).use{c->while(c.moveToNext())columns.add(ExplorerColumn(c.getString(1),c.getString(2).ifBlank{"ANY"},c.getInt(5)>0))}}
                val count=runCatching{database.rawQuery("SELECT COUNT(*) FROM \"${table.replace("\"","\"\"")}\"",null).use{c->if(c.moveToFirst())c.getLong(0) else 0L}}.getOrNull()
                ExplorerTable(table,if(count!=null)"$count rows" else "table",columns)
            }
        }
        if(::db.isInitialized && db.isOpen && File(path).absolutePath==dbFile.absolutePath){
            runCatching{read(db)}.getOrNull()?.let{if(it.isNotEmpty())return it}
        }
        val opened=try{SQLiteDatabase.openDatabase(path,null,SQLiteDatabase.OPEN_READONLY)}catch(_:Exception){SQLiteDatabase.openDatabase(path,null,SQLiteDatabase.OPEN_READWRITE)}
        return try{read(opened)}finally{opened.close()}
    }

    private fun loadExplorerTablesFromOpenDatabase(database:SQLiteDatabase):List<ExplorerTable>{
        val names=mutableListOf<String>()
        database.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata' ORDER BY name",null).use{c->{while(c.moveToNext())names.add(c.getString(0))}}
        return names.map{table->
            val columns=mutableListOf<ExplorerColumn>()
            runCatching{database.rawQuery("PRAGMA table_info('${table.replace("'","''")}')",null).use{c->{while(c.moveToNext())columns.add(ExplorerColumn(c.getString(1),c.getString(2).ifBlank{"ANY"},c.getInt(5)>0))}}}
            val count=runCatching{database.rawQuery("SELECT COUNT(*) FROM \"${table.replace("\"","\"\"")}\"",null).use{c->if(c.moveToFirst())c.getLong(0) else 0L}}.getOrNull()
            ExplorerTable(table,if(count!=null)"$count rows" else "table",columns)
        }
    }

    private fun addDatabaseTreeNode(parent:LinearLayout,label:String,path:String,sample:Boolean){
        val active=File(path).absolutePath==dbFile.absolutePath
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(40);setPadding(dp(4),0,dp(4),0);background=if(active)rounded(Color.rgb(34,35,42),1,Color.rgb(66,68,77),9f) else ColorDrawable(Color.TRANSPARENT);tag="schema:$label";isClickable=true;contentDescription=if(active)"Selected database $label" else "Database $label"}
        val chevron=TextView(this).apply{text="▸";textSize=17f;setTextColor(blue);gravity=Gravity.CENTER;layoutParams=LinearLayout.LayoutParams(dp(28),dp(40))}
        val icon=TextView(this).apply{text="▰";textSize=12f;setTextColor(if(active)blue else Color.rgb(150,145,163));gravity=Gravity.CENTER;layoutParams=LinearLayout.LayoutParams(dp(24),dp(40))}
        val name=TextView(this).apply{this.text=label;textSize=12f;setTextColor(white);setTypeface(null,if(active)android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL);gravity=Gravity.CENTER_VERTICAL;ellipsize=android.text.TextUtils.TruncateAt.END;maxLines=1;layoutParams=LinearLayout.LayoutParams(0,dp(40),1f)}
        val cached=databaseSummarySafe(path);val meta=TextView(this).apply{this.text=if(active&&cached.isNotBlank())"active · $cached" else cached;textSize=9f;setTextColor(if(active)Color.rgb(157,154,166) else Color.rgb(118,122,134));gravity=Gravity.CENTER_VERTICAL;layoutParams=LinearLayout.LayoutParams(-2,dp(40))}
        row.addView(chevron);row.addView(icon);row.addView(name);row.addView(meta);parent.addView(row)
        val children=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE;setPadding(dp(18),0,0,0);tag="schema:${label}_contents"};parent.addView(children)
        var expanded=false;var loaded=false
        fun loadChildrenAsync(){
            children.removeAllViews();children.addView(TextView(this).apply{text="Loading tables…";textSize=10f;setTextColor(Color.rgb(125,129,141));setPadding(dp(14),dp(8),0,dp(8))})
            backgroundExecutor.execute{
                var tables=emptyList<ExplorerTable>(); var loadError:String?=null
                try{
                    // Read the explorer from the database file itself. This avoids racing the
                    // active connection when the user switches databases while the tree is loading.
                    tables=loadExplorerTables(File(path).absolutePath)
                }catch(e:Exception){loadError=e.message}
                runOnUiThread{if(isFinishing||isDestroyed||!expanded)return@runOnUiThread;children.removeAllViews();when{loadError!=null->children.addView(TextView(this).apply{text="Could not read database: $loadError";textSize=10f;setTextColor(red);setPadding(dp(14),dp(7),dp(8),dp(7))});tables.isEmpty()->children.addView(TextView(this).apply{text="No user tables";textSize=10f;setTextColor(Color.rgb(125,129,141));setPadding(dp(14),dp(7),0,dp(7))});else->{children.addView(TextView(this).apply{text="TABLES";textSize=9f;letterSpacing=.12f;setTextColor(Color.rgb(105,109,122));setTypeface(null,android.graphics.Typeface.BOLD);setPadding(dp(8),dp(7),0,dp(4))});tables.forEach{addDatabaseObjectNode(children,it,path,label)}}};loaded=true}
            }
        }
        fun setExpanded(v:Boolean){expanded=v;chevron.text=if(v)"▾" else "▸";if(v){children.visibility=View.VISIBLE;children.alpha=0f;children.animate().alpha(1f).setDuration(130).start();if(!loaded)loadChildrenAsync()}else children.animate().alpha(0f).setDuration(90).withEndAction{children.visibility=View.GONE}.start()}
        row.setOnClickListener{if(File(path).absolutePath!=dbFile.absolutePath){if(!File(path).exists()&&sample)runCatching{copyBundledDatabase("Parks_and_Recreation.db")};activateDatabase(path,label)}else setExpanded(!expanded)}
        chevron.setOnClickListener{setExpanded(!expanded)};row.setOnLongClickListener{if(!sample)showDatabaseActions(label,path);!sample};if(android.os.Build.VERSION.SDK_INT>=23&&!sample)row.setOnContextClickListener{showDatabaseDeleteMenu(row,label,path);true}
    }

    private fun addDatabaseObjectNode(parent:LinearLayout,table:ExplorerTable,path:String,label:String){
        val safe=table.name.replace("\"","\"\"")
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(36);setPadding(0,0,dp(4),0);tag="schema:${table.name}";isClickable=true}
        row.addView(TextView(this).apply{text="▦";textSize=12f;setTextColor(Color.rgb(166,160,177));gravity=Gravity.CENTER;layoutParams=LinearLayout.LayoutParams(dp(28),dp(36))})
        row.addView(TextView(this).apply{text=table.name;textSize=11.5f;setTextColor(white);gravity=Gravity.CENTER_VERTICAL;ellipsize=android.text.TextUtils.TruncateAt.END;maxLines=1;layoutParams=LinearLayout.LayoutParams(0,dp(36),1f)})
        row.addView(TextView(this).apply{text=table.rowCount;textSize=9f;setTextColor(Color.rgb(118,122,134));gravity=Gravity.CENTER_VERTICAL;layoutParams=LinearLayout.LayoutParams(-2,dp(36))})
        parent.addView(row)
        row.setOnClickListener{try{if(File(path).absolutePath!=dbFile.absolutePath)activateDatabase(path,label);insertSql("SELECT * FROM \"$safe\";\n");showTablePreviewInline(table.name)}catch(e:Exception){showError("Could not open ${table.name}: ${e.message?:"table error"}")}}
        row.setOnLongClickListener{if(File(path).absolutePath==dbFile.absolutePath)showTableContextMenu(table.name);true}
        val columns=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE;setPadding(dp(28),0,0,dp(4))}
        val columnsRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(32);setPadding(0,0,dp(4),0)}
        val cc=TextView(this).apply{text="▸";textSize=16f;setTextColor(Color.rgb(155,150,168));gravity=Gravity.CENTER;layoutParams=LinearLayout.LayoutParams(dp(28),dp(32))}
        columnsRow.addView(cc);columnsRow.addView(TextView(this).apply{text="Columns";textSize=10.5f;setTextColor(Color.rgb(196,192,202));gravity=Gravity.CENTER_VERTICAL;layoutParams=LinearLayout.LayoutParams(0,dp(32),1f)})
        parent.addView(columnsRow);parent.addView(columns)
        var columnsExpanded=false;fun setColumnsExpanded(v:Boolean){columnsExpanded=v;cc.text=if(v)"▾" else "▸";columns.visibility=if(v)View.VISIBLE else View.GONE};columnsRow.setOnClickListener{setColumnsExpanded(!columnsExpanded)}
        table.columns.forEach{col->val cr=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(32);setPadding(0,0,dp(4),0);tag="schema:${col.name}"};cr.addView(TextView(this).apply{text="◆";textSize=9f;setTextColor(Color.rgb(150,145,163));gravity=Gravity.CENTER;layoutParams=LinearLayout.LayoutParams(dp(28),dp(32))});cr.addView(TextView(this).apply{text=col.name;textSize=11f;setTextColor(white);gravity=Gravity.CENTER_VERTICAL;ellipsize=android.text.TextUtils.TruncateAt.END;maxLines=1;layoutParams=LinearLayout.LayoutParams(0,dp(32),1f)});cr.addView(TextView(this).apply{text=if(col.primaryKey)"${col.type} · PK" else col.type;textSize=9f;setTextColor(Color.rgb(125,129,141));gravity=Gravity.CENTER_VERTICAL;layoutParams=LinearLayout.LayoutParams(-2,dp(32))});cr.setOnClickListener{insertSql("\"${col.name.replace("\"","\"\"")}\"")};columns.addView(cr)}
    }
    private fun showDatabaseDeleteMenu(anchor:View,label:String,path:String){
        val popup=PopupWindow(this);val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(4),dp(4),dp(4),dp(4));background=rounded(Color.rgb(31,32,38),1,Color.rgb(76,77,85),10f);elevation=dp(10).toFloat()}
        val del=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),0,dp(10),0);isClickable=true;background=rounded(Color.TRANSPARENT,0,Color.TRANSPARENT,7f)}
        del.addView(TextView(this).apply{text="⌫";textSize=13f;setTextColor(red);gravity=Gravity.CENTER;layoutParams=LinearLayout.LayoutParams(dp(25),dp(32))})
        del.addView(TextView(this).apply{text="Delete database";textSize=12f;setTextColor(Color.rgb(238,234,238));gravity=Gravity.CENTER_VERTICAL;layoutParams=LinearLayout.LayoutParams(dp(126),dp(32))})
        del.setOnHoverListener{v,e->if(e.action==MotionEvent.ACTION_HOVER_ENTER)v.setBackgroundColor(Color.rgb(60,44,50));else if(e.action==MotionEvent.ACTION_HOVER_EXIT)v.setBackgroundColor(Color.TRANSPARENT);false};del.setOnClickListener{popup.dismiss();confirmDeleteDatabase(label,path)};root.addView(del)
        popup.contentView=root;popup.width=dp(158);popup.height=dp(40);popup.isFocusable=true;popup.isOutsideTouchable=true;popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));val loc=IntArray(2);anchor.getLocationOnScreen(loc);val x=(loc[0]+anchor.width-dp(158)).coerceAtLeast(dp(8));val y=(loc[1]+anchor.height+dp(4)).coerceAtMost(resources.displayMetrics.heightPixels-dp(48));popup.showAtLocation(anchor,Gravity.TOP or Gravity.START,x,y);animatePopupIn(root)
    }
    private fun ensureSamplePath(label:String):String{
        val asset=if(label=="Northwind")"Northwind.db" else "Parks_and_Recreation.db"
        val f=File(filesDir,"Sample_${asset.replace(Regex("[^A-Za-z0-9._-]"),"_")}")
        if(!f.exists())assets.open(asset).use{i->f.outputStream().use{o->i.copyTo(o)}}
        return f.path
    }
    private fun addDatabaseRow(parent:LinearLayout,label:String,path:String,sample:Boolean){
        val active=File(path).absolutePath==dbFile.absolutePath
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(44);setPadding(dp(10),0,dp(6),0);background=rounded(if(active)Color.rgb(36,38,45) else Color.TRANSPARENT,if(active)1 else 0,if(active)Color.rgb(62,64,72) else Color.TRANSPARENT,10f);isClickable=true;contentDescription=if(active)"Selected database $label" else "Select database $label"}
        val icon=TextView(this).apply{text=if(sample)"✿" else "●";textSize=11f;setTextColor(if(active)blue else Color.rgb(145,141,154));gravity=Gravity.CENTER;layoutParams=LinearLayout.LayoutParams(dp(24),dp(40))}
        val nameView=TextView(this).apply{this.text=label;textSize=12f;setTextColor(if(active)white else Color.rgb(196,192,202));setTypeface(null,if(active)android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL);gravity=Gravity.CENTER_VERTICAL;ellipsize=android.text.TextUtils.TruncateAt.END;maxLines=1;layoutParams=LinearLayout.LayoutParams(0,dp(40),1f)}
        val meta=TextView(this).apply{this.text=if(File(path).exists())databaseSummarySafe(path) else "";textSize=9f;setTextColor(Color.rgb(118,122,134));gravity=Gravity.CENTER_VERTICAL;ellipsize=android.text.TextUtils.TruncateAt.END;maxLines=1;layoutParams=LinearLayout.LayoutParams(-2,dp(40))}
        row.addView(icon);row.addView(nameView);row.addView(meta)
        row.setOnClickListener{
            if(active && !sample){databaseSwitcherPopup?.dismiss();showDatabaseActions(label,path);return@setOnClickListener}
            try{
                if(!File(path).exists() && sample){copyBundledDatabase(if(label=="Northwind")"Northwind.db" else "Parks_and_Recreation.db")}
                activateDatabase(path,label);databaseSwitcherPopup?.dismiss()
            }catch(e:Exception){showError("Could not open $label: ${e.message?:"database error"}")}
        }
        parent.addView(row,LinearLayout.LayoutParams(-1,dp(44)).apply{bottomMargin=dp(3)})
    }
    private fun databaseSummarySafe(path:String):String=databaseSummaryCache[path].orEmpty()
    private fun warmDatabaseSummary(path:String){
        if(databaseSummaryCache.containsKey(path)||!File(path).exists())return
        backgroundExecutor.execute{
            val summary=try{SQLiteDatabase.openDatabase(path,null,SQLiteDatabase.OPEN_READONLY).use{databaseSummary(it)}}catch(_:Exception){""}
            if(summary.isNotBlank())databaseSummaryCache[path]=summary
        }
    }
    private fun showDatabaseActions(label:String,path:String){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        box.addView(TextView(this).apply{text=databaseSummarySafe(path);textSize=12f;setTextColor(Color.rgb(145,141,154));setPadding(0,0,0,dp(12))})
        val delete=Button(ContextThemeWrapper(this,R.style.FlatButton)).apply{text="Delete Database";isAllCaps=false;setTextColor(red)}
        box.addView(delete,LinearLayout.LayoutParams(-1,dp(42)))
        val dialog=cardDialog(label,box);dialogRef=dialog;dialog.setOnDismissListener{if(dialogRef===dialog)dialogRef=null}
        delete.setOnClickListener{dialog.dismiss();confirmDeleteDatabase(label,path)}
        dialog.show()
    }
    private fun confirmDeleteDatabase(label:String,path:String){
        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        content.addView(TextView(this).apply{text="This database and its local data will be removed from Project Lily.";textSize=12.5f;setTextColor(white);setPadding(0,dp(8),0,dp(8))})
        content.addView(TextView(this).apply{text="This action cannot be undone.";textSize=10f;setTextColor(Color.rgb(145,141,154));setPadding(0,0,0,dp(6))})
        cardDialog("Delete $label?",content,"Confirm Delete"){
            try{
                val deletingActive=File(path).absolutePath==dbFile.absolutePath
                if(deletingActive){try{db.close()}catch(_:Exception){};File(path).delete();unregisterUserDatabase(path);copyBundledDatabase("Parks_and_Recreation.db");prefs.edit().putString("active_db_path",dbFile.path).apply()}
                else {File(path).delete();unregisterUserDatabase(path)}
                refreshSchemaPanel();statusOk("Deleted $label")
            }catch(e:Exception){showError("Delete failed: ${e.message?:"error"}")}
        }.show()
    }
    private fun tableHasColumn(table:String,filter:String):Boolean=try{db.rawQuery("PRAGMA table_info('"+table.replace("'","''")+"')",null).use{c->while(c.moveToNext())if(c.getString(1).contains(filter,true))return true};false}catch(_:Exception){false}
    private fun addTreeSection(root:LinearLayout,title:String,type:String,always:Boolean){
        val names=mutableListOf<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type=? AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata' ORDER BY name",arrayOf(type)).use{c->while(c.moveToNext()){val n=c.getString(0);if(schemaFilter.isBlank()||n.contains(schemaFilter,true)||type!="table"||tableHasColumn(n,schemaFilter))names.add(n)}}
        if(names.isEmpty()&&!always)return
        val section=TextView(this).apply{text=title;textSize=9f;letterSpacing=.14f;setTextColor(Color.rgb(112,116,129));setTypeface(null,android.graphics.Typeface.BOLD);setPadding(dp(8),dp(8),dp(8),dp(6))}
        root.addView(section)
        if(names.isEmpty()){
            root.addView(TextView(this).apply{text="No tables yet";textSize=11f;setTextColor(Color.rgb(130,134,147));setPadding(dp(16),dp(4),dp(8),dp(8))})
            return
        }
        names.forEach{name->addTreeNode(root,name,type)}
    }
    private fun addTreeNode(parent:LinearLayout,name:String,type:String){
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(38);setPadding(dp(4),0,dp(4),0);setBackgroundResource(R.drawable.tree_row);tag="schema:$name"}
        val chevron=TextView(this).apply{text="▸";textSize=18f;setTextColor(blue);gravity=Gravity.CENTER;setPadding(0,0,0,dp(2));layoutParams=LinearLayout.LayoutParams(dp(32),dp(40))}
        val icon=TextView(this).apply{text=if(type=="table")"▦" else if(type=="view")"◫" else "◇";textSize=13f;setTextColor(Color.rgb(166,160,177));gravity=Gravity.CENTER;layoutParams=LinearLayout.LayoutParams(dp(28),dp(40))}
        val label=TextView(this).apply{text=name;textSize=12f;setTextColor(white);setTypeface(null,android.graphics.Typeface.BOLD);gravity=Gravity.CENTER_VERTICAL;ellipsize=android.text.TextUtils.TruncateAt.END;maxLines=1;layoutParams=LinearLayout.LayoutParams(0,dp(40),1f)}
        val meta=TextView(this).apply{this.text=if(type=="table")tableRowCountText(name) else "";textSize=10f;setTextColor(Color.rgb(125,129,141));gravity=Gravity.CENTER_VERTICAL;layoutParams=LinearLayout.LayoutParams(-2,dp(40))}
        row.addView(chevron);row.addView(icon);row.addView(label);row.addView(meta)
        val children=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE;setPadding(dp(32),0,0,0)}
        parent.addView(row);parent.addView(children)
        var expanded=false
        fun setExpanded(value:Boolean){expanded=value;chevron.text=if(expanded)"▾" else "▸";children.visibility=if(expanded)View.VISIBLE else View.GONE}
        chevron.setOnClickListener{setExpanded(!expanded)}
        row.setOnClickListener{
            if(type=="table"){
                setExpanded(!expanded)
                showTablePreviewInline(name)
            }else insertSql(name)
        }
        if(type=="table") row.setOnLongClickListener{showTableContextMenu(name);true}
        if(type=="table"){
            val columnsRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(36);setPadding(0,0,dp(4),0)}
            val cc=TextView(this).apply{text="▸";textSize=17f;setTextColor(Color.rgb(155,150,168));gravity=Gravity.CENTER;layoutParams=LinearLayout.LayoutParams(dp(32),dp(36))}
            val ci=TextView(this).apply{text="◇";textSize=12f;setTextColor(Color.rgb(155,150,168));gravity=Gravity.CENTER;layoutParams=LinearLayout.LayoutParams(dp(28),dp(36))}
            val ct=TextView(this).apply{text="Columns";textSize=11f;setTextColor(Color.rgb(196,192,202));gravity=Gravity.CENTER_VERTICAL;layoutParams=LinearLayout.LayoutParams(0,dp(36),1f)}
            columnsRow.addView(cc);columnsRow.addView(ci);columnsRow.addView(ct);children.addView(columnsRow)
            val columns=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE;setPadding(dp(28),0,0,dp(4))}
            children.addView(columns)
            var columnsExpanded=false
            fun setColumnsExpanded(value:Boolean){columnsExpanded=value;cc.text=if(columnsExpanded)"▾" else "▸";columns.visibility=if(columnsExpanded)View.VISIBLE else View.GONE}
            cc.setOnClickListener{setColumnsExpanded(!columnsExpanded)}
            columnsRow.setOnClickListener{setColumnsExpanded(!columnsExpanded)}
            db.rawQuery("PRAGMA table_info('${name.replace("'","''")}')",null).use{c->while(c.moveToNext()){
                val col=c.getString(1);val typ=c.getString(2).ifBlank{"ANY"};val pk=c.getInt(5)>0
                val cr=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(32);setPadding(0,0,dp(4),0);tag="schema:$col"}
                cr.addView(TextView(this).apply{text="◆";textSize=9f;setTextColor(Color.rgb(150,145,163));gravity=Gravity.CENTER;layoutParams=LinearLayout.LayoutParams(dp(28),dp(32))})
                cr.addView(TextView(this).apply{text=col;textSize=11f;setTextColor(white);gravity=Gravity.CENTER_VERTICAL;ellipsize=android.text.TextUtils.TruncateAt.END;maxLines=1;layoutParams=LinearLayout.LayoutParams(0,dp(32),1f)})
                cr.addView(TextView(this).apply{text=if(pk)"$typ · PK" else typ;textSize=9f;setTextColor(Color.rgb(125,129,141));gravity=Gravity.CENTER_VERTICAL;layoutParams=LinearLayout.LayoutParams(-2,dp(32))})
                cr.setOnClickListener{insertSql("\"${col.replace("\"","\"\"")}\"")}
                columns.addView(cr)
            }}
        }
    }
    private fun showTableContextMenu(table:String){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val actions=listOf(
            "Browse Data" to {showTablePreviewInline(table)},
            "Generate SELECT" to {insertSql("SELECT * FROM \"${table.replace("\"","\"\"")}\";\n")},
            "Generate INSERT" to {showTableInsertTemplate(table)},
            "Structure" to {inspectTable(table)},
            "Edit Data" to {showDataEditor(table)},
            "Table Designer" to {showTableDesigner(table)},
            "Export Table as SQL" to {exportTableSql(table)},
            "Duplicate Table" to {duplicateTable(table)},
            "Rename Table" to {renameTable(table)},
            "Delete Table" to {confirmDeleteTable(table)}
        )
        val scroll=ScrollView(this).apply{isFillViewport=true;overScrollMode=View.OVER_SCROLL_NEVER}
        val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        actions.forEachIndexed{idx,pair->val b=Button(ContextThemeWrapper(this,R.style.FlatButton)).apply{text=pair.first;isAllCaps=false;setOnClickListener{pair.second()}};list.addView(b,LinearLayout.LayoutParams(-1,dp(38)).apply{if(idx>0)topMargin=dp(4)})}
        scroll.addView(list);box.addView(scroll,LinearLayout.LayoutParams(-1,dp(360)))
        cardDialog(table,box).show()
    }
    private fun duplicateTable(table:String){
        val oldSafe=table.replace("\"","\"\"")
        try{
            val base=table+"_copy";var n=base;var i=2
            while(tableNames().any{it.equals(n,true)}){n=base+i;i++}
            val ddl=executionTableDefinition(table) ?: throw Exception("Could not read table definition")
            val open=ddl.indexOf('(')
            if(open<0)throw Exception("Invalid table definition")
            val head=ddl.substring(0,open)
            val marker=Regex("(?is)^(.*?CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?)").find(head)
                ?: throw Exception("Unsupported table definition")
            val quotedNew="\"${n.replace("\"","\"\"")}\""
            val newDdl=marker.groupValues[1]+quotedNew+" "+ddl.substring(open)
            val indexSql=mutableListOf<String>()
            db.rawQuery("SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name=? AND sql IS NOT NULL",arrayOf(table)).use{c->
                while(c.moveToNext())indexSql.add(c.getString(0))
            }
            db.beginTransaction()
            try{
                db.execSQL(newDdl)
                db.execSQL("INSERT INTO $quotedNew SELECT * FROM \"$oldSafe\"")
                indexSql.forEach{sql->
                    val rewritten=sql.replace(Regex("(?i)\\b${Regex.escape(table)}\\b"),n)
                    try{db.execSQL(rewritten)}catch(_:Exception){}
                }
                db.setTransactionSuccessful()
            }finally{db.endTransaction()}
            refreshSchemaPanel();statusOk("Duplicated $table as $n")
        }catch(e:Exception){
            try{
                val base=table+"_copy";var n=base;var i=2
                while(tableNames().any{it.equals(n,true)}){n=base+i;i++}
                val newSafe=n.replace("\"","\"\"")
                db.execSQL("CREATE TABLE \"$newSafe\" AS SELECT * FROM \"$oldSafe\"")
                refreshSchemaPanel();statusOk("Duplicated $table as $n")
            }catch(inner:Exception){showError("Could not duplicate table: ${inner.message?:e.message?:"error"}")}
        }
    }
    private fun executionTableDefinition(table:String):String?=db.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name=?",arrayOf(table)).use{if(it.moveToFirst())it.getString(0) else null}
    private fun renameTable(table:String){
        val input=EditText(this).apply{setSingleLine(true);setText(table);setTextColor(white);setHintTextColor(Color.rgb(105,108,119));background=rounded(Color.rgb(28,30,38),1,Color.rgb(48,51,62),9f);setPadding(dp(10),0,dp(10),0)}
        cardDialog("Rename table",input,"Rename"){try{val n=input.text.toString().trim();if(!n.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))throw Exception("Invalid table name");db.execSQL("ALTER TABLE \"${table.replace("\"","\"\"")}\" RENAME TO \"${n.replace("\"","\"\"")}\"");refreshSchemaPanel();statusOk("Renamed to $n")}catch(e:Exception){showError("Rename failed: ${e.message?:"error"}")}}.show()
    }
    private fun confirmDeleteTable(table:String){
        cardDialog("Delete $table?",TextView(this).apply{text="This removes the table and all of its rows. This cannot be undone.";textSize=13f;setTextColor(white);setPadding(0,dp(8),0,dp(8))},"Delete"){try{db.execSQL("DROP TABLE \"${table.replace("\"","\"\"")}\"");refreshSchemaPanel();statusOk("Deleted $table")}catch(e:Exception){showError("Delete failed: ${e.message?:"error"}")}}.show()
    }
    private var pendingTableSqlExport:String?=null; private var pendingDatabaseSqlExport=false; private var pendingDatabaseExportPath:String?=null
    private fun exportTableSql(table:String){
        pendingTableSqlExport=table
        val i=Intent(Intent.ACTION_CREATE_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="application/sql";putExtra(Intent.EXTRA_TITLE,"${table}.sql")}
        startActivityForResult(i,92)
    }
    private fun streamTableSqlExport(uri:Uri,table:String){
        val executionDb=if(::db.isInitialized&&db.isOpen)db else {showError("No active database");return}
        Thread{
            try{
                val safe=table.replace("\"","\"\"")
                contentResolver.openOutputStream(uri)?.use{raw->
                    OutputStreamWriter(raw,Charsets.UTF_8).buffered().use{w->
                        executionDb.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name=?",arrayOf(table)).use{c->
                            if(!c.moveToFirst())throw Exception("Table no longer exists")
                            w.write((c.getString(0) ?: "").trimEnd(';'));w.write(";\n\n")
                        }
                        executionDb.rawQuery("SELECT * FROM \"$safe\"",null).use{c->
                            val cols=(0 until c.columnCount).map{c.getColumnName(it)}
                            val quotedCols=cols.joinToString(", "){"\"${it.replace("\"","\"\"")}\""}
                            while(c.moveToNext()){
                                val vals=(0 until c.columnCount).joinToString(", "){i->sqliteLiteral(c,i)}
                                w.write("INSERT INTO \"$safe\" ($quotedCols) VALUES ($vals);\n")
                            }
                        }
                    }
                } ?: throw Exception("Could not open output file")
                runOnUiThread{if(!isFinishing&&!isDestroyed)statusOk("Exported table $table")}
            }catch(e:Exception){runOnUiThread{if(!isFinishing&&!isDestroyed)showError("Table export failed: ${e.message?:"error"}")}}
        }.start()
    }
    private fun showTableInsertTemplate(table:String){
        val cols=mutableListOf<String>();db.rawQuery("PRAGMA table_info('${table.replace("'","''")}')",null).use{c->while(c.moveToNext())cols.add(c.getString(1))}
        if(cols.isEmpty())return
        val quoted=cols.joinToString(", "){"\"${it.replace("\"","\"\"")}\""};insertSql("INSERT INTO \"${table.replace("\"","\"\"")}\" ($quoted) VALUES (${cols.joinToString(", "){ "?" }});\n")
    }
    private fun tableRowCountText(name:String):String{
        return try{
            // Exact COUNT(*) can be surprisingly expensive on very large tables and
            // used to run for every Explorer refresh. Prefer planner statistics when
            // available; otherwise show a lightweight "table" label.
            val estimate=runCatching{db.rawQuery("SELECT stat FROM sqlite_stat1 WHERE tbl=? ORDER BY rowid DESC LIMIT 1",arrayOf(name)).use{c->if(c.moveToFirst())c.getString(0).substringBefore(' ').toLongOrNull() else null}}.getOrNull()
            if(estimate!=null)"~$estimate rows" else "table"
        }catch(_:Exception){"table"}
    }
    private fun showTablePreviewInline(table:String){
        val safe=table.replace("\"","\"\"")
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(10),dp(14),dp(10))}
        val title=TextView(this).apply{text=table;textSize=15f;setTextColor(white);setTypeface(null,android.graphics.Typeface.BOLD);setPadding(0,0,0,4)}
        box.addView(title)
        val meta=TextView(this).apply{textSize=11f;setTextColor(Color.rgb(130,134,147));setPadding(0,0,0,8)}
        meta.text="${tableColumnCount(table)} columns  ·  ${tableRowCountText(table)}  ·  first 50 shown"
        box.addView(meta)
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val select=Button(ContextThemeWrapper(this,R.style.FlatButton)).apply{text="Insert SELECT *";isAllCaps=false;setOnClickListener{insertSql("SELECT * FROM \"$safe\";")}}
        actions.addView(select)
        box.addView(actions)
        val data=mutableListOf<List<String>>()
        db.rawQuery("SELECT * FROM \"$safe\" LIMIT 50",null).use{c->
            data.add((0 until c.columnCount).map{c.getColumnName(it)})
            while(c.moveToNext())data.add((0 until c.columnCount).map{if(c.isNull(it))"NULL" else c.getString(it)})
        }
        box.addView(buildPreviewGrid(data),LinearLayout.LayoutParams(-1,0,1f))
        resultContainer.removeAllViews();resultContainer.addView(box)
        statusOk("Previewing $table")
    }
    private fun buildPreviewGrid(data:List<List<String>>):View{
        val table=TableLayout(this)
        table.isStretchAllColumns=false
        if(data.isEmpty()) return TextView(this).apply{text="No columns";setPadding(dp(12),dp(12),dp(12),dp(12));setTextColor(white)}
        val header=TableRow(this)
        for(v in data[0]) header.addView(cell(v,true){})
        table.addView(header)
        for(i in 1 until data.size){
            val row=TableRow(this)
            for(v in data[i]) row.addView(cell(v,false){copyText(v)})
            table.addView(row)
        }
        if(data.size==1) table.addView(TextView(this).apply{text="(0 rows)";setPadding(dp(16),dp(16),dp(16),dp(16));setTextColor(white)})
        val hs=HorizontalScrollView(this)
        hs.addView(table)
        val vs=ScrollView(this)
        vs.addView(hs)
        return vs
    }
    private fun tableColumnCount(table:String):Int{var n=0;db.rawQuery("PRAGMA table_info('${table.replace("'","''")}')",null).use{c->while(c.moveToNext())n++};return n}
    private fun showSchema(){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(0));minimumHeight=dp(420)}
        val hint=TextView(this).apply{text="Select a database above. Expand a table to browse columns.";textSize=12f;setTextColor(Color.rgb(128,132,145));setPadding(0,0,0,dp(10))}
        box.addView(hint)
        val import=Button(ContextThemeWrapper(this,R.style.FlatButton)).apply{text="Import .db / .sql";isAllCaps=false;setOnClickListener{pickFile()}}
        box.addView(import,LinearLayout.LayoutParams(-1,dp(42)).apply{bottomMargin=dp(10)})
        val scroll=ScrollView(this);scroll.addView(interactiveSchemaView());box.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        cardDialog("Database Explorer",box).show()
    }
    private fun refreshSchemaPanel(){
        val generation=++schemaRefreshGeneration
        schemaPanel?.let{it.removeAllViews();it.addView(interactiveSchemaView(),LinearLayout.LayoutParams(-1,-2))}
        val missing=databaseTreePaths().map{it.second}.distinct().filter{databaseSummaryCache[it].isNullOrBlank()}
        if(missing.isEmpty())return
        backgroundExecutor.execute{
            missing.forEach{path->if(File(path).exists()){val summary=try{SQLiteDatabase.openDatabase(path,null,SQLiteDatabase.OPEN_READONLY).use{databaseSummary(it)}}catch(_:Exception){""};if(summary.isNotBlank())databaseSummaryCache[path]=summary}}
            runOnUiThread{if(generation==schemaRefreshGeneration&&!isFinishing&&!isDestroyed){schemaPanel?.let{it.removeAllViews();it.addView(interactiveSchemaView(),LinearLayout.LayoutParams(-1,-2))}}}
        }
    }
    private fun firstTable():String{db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata' ORDER BY name LIMIT 1",null).use{c->if(c.moveToFirst())return c.getString(0)};return ""}
    private fun chooseDatabase(){showSchema()}
    internal fun statusOk(s:String){status.text="✓ $s";status.setTextColor(green)};private fun showError(s:String){status.text="✕ $s";status.setTextColor(red)}
    private fun formatSql(sql:String):String{
        if(sql.isBlank())return sql
        fun mask(source:String):String{
            val out=StringBuilder(source.length);var quote:Char?=null;var bracket=false;var backtick=false;var line=false;var block=false;var i=0
            while(i<source.length){val c=source[i];val n=if(i+1<source.length)source[i+1] else '\u0000'
                if(line){out.append(if(c=='\n')'\n' else ' ');if(c=='\n')line=false;i++;continue}
                if(block){if(c=='*'&&n=='/'){out.append("  ");i+=2;block=false}else{out.append(if(c=='\n')'\n' else ' ');i++};continue}
                if(quote!=null){out.append(if(c=='\n')'\n' else ' ');if(c==quote){if(n==quote){out.append(' ');i+=2;continue};quote=null};i++;continue}
                if(bracket){out.append(' ');if(c==']'){if(n==']'){out.append(' ');i+=2;continue};bracket=false};i++;continue}
                if(backtick){out.append(' ');if(c=='`'){if(n=='`'){out.append(' ');i+=2;continue};backtick=false};i++;continue}
                if(c=='-'&&n=='-'){out.append("  ");i+=2;line=true;continue};if(c=='/'&&n=='*'){out.append("  ");i+=2;block=true;continue}
                if(c=='\''||c=='"'){out.append(' ');quote=c;i++;continue};if(c=='['){out.append(' ');bracket=true;i++;continue};if(c=='`'){out.append(' ');backtick=true;i++;continue}
                out.append(c);i++
            }
            return out.toString()
        }
        var text=sql.replace("\r\n","\n").replace('\r','\n').trim()
        val masked=mask(text)
        val patterns=listOf(
            Regex("\\s+(SELECT|FROM|WHERE|GROUP\\s+BY|ORDER\\s+BY|HAVING|LIMIT|OFFSET|UNION(?:\\s+ALL)?|INTERSECT|EXCEPT|VALUES|SET|RETURNING)\\b",RegexOption.IGNORE_CASE),
            Regex("\\s+((?:LEFT|RIGHT|FULL|INNER|OUTER|CROSS)\\s+)?JOIN\\s+",RegexOption.IGNORE_CASE),
            Regex("\\s+ON\\s+",RegexOption.IGNORE_CASE)
        )
        val positions=mutableListOf<Int>();patterns.forEach{r->r.findAll(masked).forEach{m->positions.add(m.range.first)}}
        for(pos in positions.distinct().sortedDescending()) if(pos>0&&pos<text.length) text=text.substring(0,pos).trimEnd()+"\n"+text.substring(pos).trimStart()
        return text.replace(Regex("\\n{3,}"),"\n\n").trim()
    }
    private fun clearEditor(){
        if(editor.text.isNullOrBlank()){return}
        val message = TextView(this).apply {
            setText("Remove all SQL from the current query tab?")
            setTextColor(white)
            textSize = 13f
            setPadding(0, dp(6), 0, dp(6))
        }
        cardDialog("Clear editor", message, "Clear") {
            editor.setText("")
            editor.requestFocus()
        }.show()
    }
    internal fun toggleComment(){
        val start=minOf(editor.selectionStart.coerceAtLeast(0),editor.selectionEnd.coerceAtLeast(0))
        val end=maxOf(editor.selectionStart.coerceAtLeast(0),editor.selectionEnd.coerceAtLeast(0))
        val text=editor.text.toString();if(text.isEmpty())return
        val lineStart=text.lastIndexOf('\n',start-1).let{if(it<0)0 else it+1}
        val selectionEnd=if(start==end)end else end
        val lineEnd=text.indexOf('\n',selectionEnd).let{if(it<0)text.length else it}
        val block=text.substring(lineStart,lineEnd)
        val lines=block.split('\n')
        val shouldUncomment=lines.filter{it.trim().isNotEmpty()}.all{it.trimStart().startsWith("--")}
        val replacement=lines.joinToString("\n"){line->
            if(line.trim().isEmpty())line
            else if(shouldUncomment){val idx=line.indexOf("--");if(idx>=0)line.removeRange(idx,minOf(idx+2,line.length)).removePrefix(" ").removePrefix(" ") else line}
            else {val indent=line.takeWhile{it==' '||it=='\t'};indent+"-- "+line.drop(indent.length)}
        }
        editor.text.replace(lineStart,lineEnd,replacement)
        val delta=replacement.length-block.length
        val newStart=(if(start>lineEnd)start+delta else start).coerceIn(0,editor.length())
        val newEnd=(if(end>lineEnd)end+delta else (end+delta).coerceIn(newStart,editor.length())).coerceIn(0,editor.length())
        editor.setSelection(if(start==end)newStart else newStart,newEnd)
        highlight()
    }
    private fun scheduleHighlight(){
        highlightRunnable?.let{mainHandler.removeCallbacks(it)}
        val r=Runnable{if(!isFinishing&&!isDestroyed)highlight()};highlightRunnable=r;mainHandler.postDelayed(r,80L)
    }
    private fun highlight(){
        if(highlighting)return;highlighting=true
        val e=editor.text;val pos=editor.selectionStart
        e.getSpans(0,e.length,ForegroundColorSpan::class.java).forEach{e.removeSpan(it)}
        val text=e.toString()
        val keywordColor=Color.rgb(111,181,214)
        val clauseColor=Color.rgb(190,166,218)
        val functionColor=Color.rgb(215,169,222)
        val stringColor=Color.rgb(153,198,153)
        val numberColor=Color.rgb(205,181,137)
        val commentColor=Color.rgb(112,117,124)
        val identifierColor=Color.rgb(224,225,227)
        val patterns=listOf(
            Pattern.compile("(?i)\\b(?:SELECT|FROM|WHERE|INSERT|INTO|VALUES|UPDATE|SET|DELETE|CREATE|TABLE|DROP|ALTER|TRUNCATE|REPLACE|SHOW|DESCRIBE|DESC|USE|WITH|RECURSIVE|RETURNING|EXPLAIN|PRAGMA)\\b") to keywordColor,
            Pattern.compile("(?i)\\b(?:JOIN|INNER|LEFT|RIGHT|FULL|OUTER|CROSS|NATURAL|ON|USING|GROUP\\s+BY|ORDER\\s+BY|HAVING|LIMIT|OFFSET|UNION(?:\\s+ALL)?|INTERSECT|EXCEPT|DISTINCT|AS|AND|OR|NOT|IS|LIKE|IN|BETWEEN|ESCAPE|CASE|WHEN|THEN|ELSE|END|OVER|PARTITION|ROWS|RANGE|GROUPS|ASC|DESC)\\b") to clauseColor,
            Pattern.compile("(?i)\\b(?:COUNT|SUM|AVG|MIN|MAX|TOTAL|GROUP_CONCAT|ROW_NUMBER|RANK|DENSE_RANK|NTILE|LAG|LEAD|FIRST_VALUE|LAST_VALUE|NTH_VALUE|FILTER|COALESCE|NULLIF|IFNULL|CAST|CONCAT|IF|DATE_FORMAT|NOW|CURDATE|CURTIME)\\s*(?=\\()") to functionColor,
            Pattern.compile("'(?:''|[^'])*'") to stringColor,
            Pattern.compile("--[^\\n]*") to commentColor,
            Pattern.compile("/\\*[\\s\\S]*?\\*/") to commentColor,
            Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b") to numberColor,
            Pattern.compile("`[^`]+`|\\\"[^\\\"]+\\\"") to identifierColor
        )
        for((p,col) in patterns)p.matcher(text).run{while(find())e.setSpan(ForegroundColorSpan(col),start(),end(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
        editor.setSelection(pos.coerceIn(0,e.length));highlighting=false
    }
    internal fun showFindReplace(){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        fun field(hint:String):EditText=EditText(this).apply{this.hint=hint;setSingleLine(true);textSize=13f;setTextColor(white);setHintTextColor(Color.rgb(105,108,119));setPadding(dp(12),0,dp(12),0);background=rounded(Color.rgb(28,30,38),1,Color.rgb(48,51,62),10f)}
        val find=field("Find");val replace=field("Replace with")
        box.addView(find,LinearLayout.LayoutParams(-1,dp(40)));box.addView(replace,LinearLayout.LayoutParams(-1,dp(40)).apply{topMargin=dp(6)})
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val next=Button(ContextThemeWrapper(this,R.style.FlatButton)).apply{text="Find next";isAllCaps=false}
        val repl=Button(ContextThemeWrapper(this,R.style.FlatButton)).apply{text="Replace";isAllCaps=false}
        val all=Button(ContextThemeWrapper(this,R.style.FlatButton_Accent)).apply{text="Replace all";isAllCaps=false}
        row.addView(next,LinearLayout.LayoutParams(0,dp(36),1f));row.addView(repl,LinearLayout.LayoutParams(0,dp(36),1f).apply{marginStart=dp(4)});row.addView(all,LinearLayout.LayoutParams(0,dp(36),1f).apply{marginStart=dp(4)})
        box.addView(row,LinearLayout.LayoutParams(-1,dp(36)).apply{topMargin=dp(7)})
        val info=TextView(this).apply{textSize=11f;setTextColor(Color.rgb(128,132,145));setPadding(0,dp(8),0,0)};box.addView(info)
        val d=cardDialog("Find & Replace",box);d.show()
        fun selectNext(){val q=find.text.toString();if(q.isBlank()){info.text="Enter text to find.";return};val text=editor.text.toString();val start=(editor.selectionEnd.coerceAtLeast(0)).coerceAtMost(text.length);val hit=text.indexOf(q,start,true).let{if(it>=0)it else text.indexOf(q,0,true)};if(hit>=0){editor.requestFocus();editor.setSelection(hit,hit+q.length);info.text="Found at position $hit"}else info.text="No match found."}
        next.setOnClickListener{selectNext()}
        repl.setOnClickListener{val q=find.text.toString();if(q.isBlank()){info.text="Enter text to find.";return@setOnClickListener};val a=editor.selectionStart;val z=editor.selectionEnd;if(a!=z&&editor.text.substring(a,z).equals(q,true)){editor.text.replace(a,z,replace.text.toString());info.text="Replaced 1 match."}else{selectNext();info.text="Select a match, then tap Replace."}}
        all.setOnClickListener{val q=find.text.toString();if(q.isBlank()){info.text="Enter text to find.";return@setOnClickListener};val old=editor.text.toString();val replText=replace.text.toString();val out=Regex(Regex.escape(q),RegexOption.IGNORE_CASE).replace(old,replText);val n=(old.length-out.length+replText.length-1).coerceAtLeast(0);editor.setText(out);editor.setSelection(editor.length());info.text="Replacement complete."}
    }
    private fun showGoToLine(){
        val input=EditText(this).apply{hint="Line number";inputType=android.text.InputType.TYPE_CLASS_NUMBER;setSingleLine(true);textSize=14f;setTextColor(white);setHintTextColor(Color.rgb(105,108,119));setPadding(dp(12),0,dp(12),0);background=rounded(Color.rgb(28,30,38),1,Color.rgb(48,51,62),10f)}
        cardDialog("Go to line",input,"Go"){val line=(input.text.toString().toIntOrNull()?:1).coerceAtLeast(1);val layout=editor.layout;val target=if(layout!=null&&line-1<layout.lineCount)layout.getLineStart(line-1) else editor.length();editor.requestFocus();editor.setSelection(target);editor.post{editor.scrollTo(0,(line-1)*editor.lineHeight);};statusOk("Moved to line $line")}.show()
        input.requestFocus()
    }
    private fun tableNames():List<String>{val out=mutableListOf<String>();db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata' ORDER BY name",null).use{c->while(c.moveToNext())out.add(c.getString(0))};return out}
    private fun showTableDesignerChooser(){
        val items=listOf("＋  Create new table")+tableNames()
        cardListDialog("Table Designer",items){i->if(i==0)showCreateTableDesigner()else showTableDesigner(items[i])}
    }
    private fun showCreateTableDesigner(){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val name=EditText(this).apply{hint="Table name";setSingleLine(true);setTextColor(white);setHintTextColor(Color.rgb(105,108,119));background=rounded(Color.rgb(28,30,38),1,Color.rgb(48,51,62),10f);setPadding(dp(12),0,dp(12),0)}
        val cols=EditText(this).apply{hint="Columns — one per line: id INTEGER PK\nname TEXT NOT NULL\nsalary NUMERIC";setTextColor(white);setHintTextColor(Color.rgb(105,108,119));gravity=Gravity.TOP;inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE;minLines=6;background=rounded(Color.rgb(28,30,38),1,Color.rgb(48,51,62),10f);setPadding(dp(12),dp(10),dp(12),dp(10))}
        box.addView(name,LinearLayout.LayoutParams(-1,dp(44)));box.addView(cols,LinearLayout.LayoutParams(-1,dp(150)).apply{topMargin=dp(8)})
        cardDialog("Create table",box,"Create"){try{val tn=name.text.toString().trim();if(!tn.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))throw Exception("Invalid table name");val defs=cols.text.toString().lines().map{it.trim()}.filter{it.isNotBlank()}.map{line->val parts=line.split(Regex("\\s+"),2);if(parts.size<2)throw Exception("Each column needs a name and type");val cn=parts[0];if(!cn.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))throw Exception("Invalid column name: $cn");"\"${cn.replace("\"","\"\"")}\" ${parts[1]}"}.joinToString(", ");if(defs.isBlank())throw Exception("Add at least one column");db.execSQL("CREATE TABLE \"${tn.replace("\"","\"\"")}\" ($defs)");refreshSchemaPanel();statusOk("Table created: $tn")}catch(e:Exception){showError("Could not create table: ${e.message?:"error"}")}}.show()
    }
    private fun showTableDesigner(table:String){
        val safe=table.replace("\"","\"\"")
        data class Def(var name:String,var type:String,var pk:Boolean=false,var notNull:Boolean=false,var unique:Boolean=false,var defaultValue:String="")
        val defs=mutableListOf<Def>()
        db.rawQuery("PRAGMA table_info('${table.replace("'","''")}')",null).use{c->while(c.moveToNext()){defs.add(Def(c.getString(1),c.getString(2).ifBlank{"TEXT"},c.getInt(5)>0,c.getInt(3)>0,false,(c.getString(4) ?: "").trim()))}}
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val hint=TextView(this).apply{text="Edit the table structure. Changes rebuild the table and preserve values for matching columns.";textSize=10.5f;setTextColor(Color.rgb(128,132,145));setPadding(0,0,0,dp(8))};box.addView(hint)
        val scroll=ScrollView(this);val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};scroll.addView(list);box.addView(scroll,LinearLayout.LayoutParams(-1,dp(250)))
        fun field(value:String,hintText:String):EditText=EditText(this).apply{setSingleLine(true);setText(value);setHint(hintText);setTextColor(white);setHintTextColor(Color.rgb(95,99,110));textSize=11f;background=rounded(Color.rgb(28,30,38),1,Color.rgb(48,51,62),8f);setPadding(dp(8),0,dp(8),0)}
        fun check(labelText:String,checked:Boolean,onChange:(Boolean)->Unit):CheckBox=CheckBox(this).apply{text=labelText;isChecked=checked;textSize=10f;setTextColor(Color.rgb(190,186,197));buttonTintList=android.content.res.ColorStateList.valueOf(Color.rgb(194,196,200));setOnCheckedChangeListener{_,v->onChange(v)}}
        fun render(){
            list.removeAllViews()
            val header=TextView(this).apply{text="COLUMN   TYPE                 OPTIONS";textSize=9f;setTextColor(Color.rgb(112,116,129));setPadding(dp(4),dp(2),0,dp(5))};list.addView(header)
            defs.forEachIndexed{index,d->
                val row=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(7),dp(7),dp(7),dp(7));background=rounded(Color.rgb(24,26,33),1,Color.rgb(45,47,57),9f)}
                val top=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
                val name=field(d.name,"column");val type=field(d.type,"type")
                top.addView(name,LinearLayout.LayoutParams(0,dp(38),1.15f));top.addView(type,LinearLayout.LayoutParams(0,dp(38),.9f).apply{marginStart=dp(6)})
                val remove=Button(ContextThemeWrapper(this,R.style.FlatButton)).apply{text="×";isAllCaps=false;setTextColor(red);setOnClickListener{defs.removeAt(index);render()}};top.addView(remove,LinearLayout.LayoutParams(dp(42),dp(38)).apply{marginStart=dp(6)})
                row.addView(top)
                val opts=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
                opts.addView(check("PK",d.pk){d.pk=it},LinearLayout.LayoutParams(0,dp(38),.75f));opts.addView(check("NOT NULL",d.notNull){d.notNull=it},LinearLayout.LayoutParams(0,dp(38),1.15f));opts.addView(check("UNIQUE",d.unique){d.unique=it},LinearLayout.LayoutParams(0,dp(38),.9f))
                row.addView(opts)
                val def=field(d.defaultValue,"DEFAULT expression (optional)");row.addView(def,LinearLayout.LayoutParams(-1,dp(36)).apply{topMargin=dp(2)})
                fun sync(){d.name=name.text.toString().trim();d.type=type.text.toString().trim();d.defaultValue=def.text.toString().trim()}
                name.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){d.name=s?.toString()?.trim().orEmpty()};override fun afterTextChanged(s:Editable?){}})
                type.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){d.type=s?.toString()?.trim().orEmpty()};override fun afterTextChanged(s:Editable?){}})
                def.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){d.defaultValue=s?.toString()?.trim().orEmpty()};override fun afterTextChanged(s:Editable?){}})
                name.setOnFocusChangeListener{_,has->if(!has)sync()};type.setOnFocusChangeListener{_,has->if(!has)sync()};def.setOnFocusChangeListener{_,has->if(!has)sync()}
                list.addView(row,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(6)})
            }
        }
        val add=Button(ContextThemeWrapper(this,R.style.FlatButton)).apply{text="＋  Add column";isAllCaps=false;setOnClickListener{defs.add(Def("column${defs.size+1}","TEXT"));render()}}
        val preview=Button(ContextThemeWrapper(this,R.style.FlatButton)).apply{text="SQL preview";isAllCaps=false;setOnClickListener{defs.forEach{it.name=it.name.trim();it.type=it.type.trim()};showTableDesignerSql(table,defs.map{it.name to it.type})}}
        box.addView(add,LinearLayout.LayoutParams(-1,dp(38)).apply{topMargin=dp(6)});box.addView(preview,LinearLayout.LayoutParams(-1,dp(38)).apply{topMargin=dp(5)})
        cardDialog("Design · $table",box,"Apply"){
            try{
                defs.forEach{it.name=it.name.trim();it.type=it.type.trim();it.defaultValue=it.defaultValue.trim();if(!it.name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))throw Exception("Invalid column name: ${it.name}");if(it.type.isBlank())throw Exception("Column ${it.name} needs a type")}
                val pk=defs.filter{it.pk};if(pk.size>1)throw Exception("Use one primary key column in this editor")
                val tableQuoted="\"$safe\"";val temp="__lily_edit_${System.currentTimeMillis()}"
                val defsSql=defs.joinToString(", "){d->"\"${d.name}\" ${d.type}"+(if(d.pk)" PRIMARY KEY" else "")+(if(d.notNull)" NOT NULL" else "")+(if(d.unique)" UNIQUE" else "")+(if(d.defaultValue.isNotBlank())" DEFAULT ${d.defaultValue}" else "")}
                val oldCols=mutableListOf<String>();db.rawQuery("PRAGMA table_info('${table.replace("'","''")}')",null).use{c->while(c.moveToNext())oldCols.add(c.getString(1))};val keep=defs.map{it.name}.filter{n->oldCols.any{it.equals(n,true)}}
                val indexSql=mutableListOf<String>();db.rawQuery("SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name=? AND sql IS NOT NULL",arrayOf(table)).use{c->while(c.moveToNext())indexSql.add(c.getString(0))}
                db.beginTransaction();try{db.execSQL("CREATE TABLE \"$temp\" ($defsSql)");if(keep.isNotEmpty()){val cols=keep.joinToString(","){"\"${it.replace("\"","\"\"")}\""};db.execSQL("INSERT INTO \"$temp\" ($cols) SELECT $cols FROM $tableQuoted")};db.execSQL("DROP TABLE $tableQuoted");db.execSQL("ALTER TABLE \"$temp\" RENAME TO $tableQuoted");indexSql.forEach{sql->try{db.execSQL(sql)}catch(_:Exception){}};db.setTransactionSuccessful()}finally{db.endTransaction()}
                refreshSchemaPanel();statusOk("Table updated: $table")
            }catch(e:Exception){showError("Could not apply design: ${e.message?:"error"}")}
        }.show()
        render()
    }
    private fun showTableDesignerSql(table:String,columns:List<Pair<String,String>>){
        val sql="CREATE TABLE \"${table.replace("\"","\"\"")}\" (\n"+columns.joinToString(",\n"){(n,t)->"  \"${n.replace("\"","\"\"")}\" ${t.ifBlank{"TEXT"}}"}+"\n);"
        val previewText=EditText(this).apply{setText(sql);setTextColor(white);textSize=11f;gravity=Gravity.TOP;minLines=8;setTextIsSelectable(true);background=rounded(Color.rgb(24,26,33),1,Color.rgb(48,51,62),9f);setPadding(dp(10),dp(8),dp(10),dp(8))}
        cardDialog("SQL Preview",previewText,"Insert"){insertSql(sql+"\n")}.show()
    }
    private fun showDataEditorChooser(){cardListDialog("Data Editor",tableNames()){i->showDataEditor(tableNames()[i])}}
    private fun showDataEditor(table:String){
        val safe=table.replace("\"","\"\"");val pk=mutableListOf<Pair<String,Int>>();db.rawQuery("PRAGMA table_info('${table.replace("'","''")}')",null).use{c->while(c.moveToNext())if(c.getInt(5)>0)pk.add(c.getString(1) to c.getInt(5))};if(pk.isEmpty()){showError("$table has no primary key; safe row editing needs a primary key");return};val pkName=pk.minBy{it.second}.first
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val hint=TextView(this).apply{text="Tap a row to edit it. Primary key: $pkName";textSize=11f;setTextColor(Color.rgb(128,132,145));setPadding(0,0,0,dp(8))};box.addView(hint)
        val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val scroll=ScrollView(this);scroll.addView(list);box.addView(scroll,LinearLayout.LayoutParams(-1,dp(300)))
        fun render(){list.removeAllViews();db.rawQuery("SELECT * FROM \"$safe\" LIMIT 100",null).use{c->val headers=(0 until c.columnCount).map{c.getColumnName(it)};while(c.moveToNext()){val values=(0 until c.columnCount).map{if(c.isNull(it))"NULL" else c.getString(it)};val row=TextView(this);row.text=values.joinToString("  ·  ");row.textSize=11f;row.setTextColor(white);row.setSingleLine(true);row.ellipsize=android.text.TextUtils.TruncateAt.END;row.setPadding(dp(10),dp(9),dp(10),dp(9));row.background=rounded(Color.rgb(28,30,38),1,Color.rgb(48,51,62),8f);val pkIdx=headers.indexOfFirst{it.equals(pkName,true)};val pkValue=values.getOrElse(pkIdx){""};row.setOnClickListener{editDataRow(table,headers,values,pkName,pkValue){render()}};list.addView(row,LinearLayout.LayoutParams(-1,dp(36)).apply{bottomMargin=dp(4)})}}}
        render();cardDialog("Data · $table",box).show()
    }
    private fun editDataRow(table:String,headers:List<String>,values:List<String>,pkName:String,pkValue:String,onSaved:()->Unit){
        val safe=table.replace("\"","\"\"")
        val sets=headers.filterNot{it.equals(pkName,true)}.map{ "\"${it.replace("\"","\"\"")}\"=?" }
        if(sets.isEmpty()){
            showError("$table only has a primary key column; there are no editable fields")
            return
        }
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val inputs=mutableListOf<EditText>()
        headers.forEachIndexed{i,h->
            val e=EditText(this).apply{
                setSingleLine(true);setText(if(values.getOrNull(i)=="NULL")"" else values.getOrNull(i).orEmpty())
                setTextColor(white);setHintTextColor(Color.rgb(105,108,119));hint=h;setPadding(dp(10),0,dp(10),0)
                background=rounded(Color.rgb(28,30,38),1,Color.rgb(48,51,62),8f)
                isEnabled=!h.equals(pkName,true)
            }
            inputs.add(e);box.addView(e,LinearLayout.LayoutParams(-1,dp(40)).apply{bottomMargin=dp(5)})
        }
        cardDialog("Edit row",box,"Save"){
            try{
                val args=headers.filterNot{it.equals(pkName,true)}.map{h->inputs[headers.indexOf(h)].text.toString()}.toMutableList()
                args.add(pkValue)
                val sql="UPDATE \"$safe\" SET ${sets.joinToString(", ")} WHERE \"${pkName.replace("\"","\"\"")}\"=?"
                db.execSQL(sql,args.toTypedArray());statusOk("Row updated");onSaved()
            }catch(e:Exception){showError("Update failed: ${e.message?:"error"}")}
        }.show()
    }
    private fun showSchemaInspector(){
        val names=tableNames();if(names.isEmpty()){showError("No tables in the active database");return};cardListDialog("Schema Inspector",names){i->inspectTable(names[i])}
    }
    private fun inspectTable(table:String){
        val safe=table.replace("\"","\"\"");val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val summary=TextView(this).apply{textSize=11f;setTextColor(Color.rgb(150,146,158));setPadding(0,0,0,dp(8))};val cols=StringBuilder("COLUMNS\n");db.rawQuery("PRAGMA table_info('${table.replace("'","''")}')",null).use{c->while(c.moveToNext())cols.append("• ").append(c.getString(1)).append("  ").append(c.getString(2).ifBlank{"ANY"}).append(if(c.getInt(5)>0)"  PK" else "").append(if(c.getInt(3)>0)"  NOT NULL" else "").append("\n")};val idx=StringBuilder("\nINDEXES\n");db.rawQuery("PRAGMA index_list('${table.replace("'","''")}')",null).use{c->while(c.moveToNext())idx.append("• ").append(c.getString(1)).append(if(c.getInt(2)>0)"  UNIQUE" else "").append("\n")};val fk=StringBuilder("\nFOREIGN KEYS\n");db.rawQuery("PRAGMA foreign_key_list('${table.replace("'","''")}')",null).use{c->while(c.moveToNext())fk.append("• ").append(c.getString(3)).append(" → ").append(c.getString(2)).append(".").append(c.getString(4)).append("\n")};summary.text="${tableColumnCount(table)} columns  ·  ${tableRowCountText(table)}\n\n$cols$idx$fk";body.addView(summary);body.addView(TextView(this).apply{text="SQL definition";textSize=11f;setTextColor(Color.rgb(128,132,145));setPadding(0,dp(8),0,dp(4))});db.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name=?",arrayOf(table)).use{c->if(c.moveToFirst())body.addView(TextView(this).apply{setText(c.getString(0));textSize=11f;setTextColor(Color.rgb(190,186,197));setPadding(0,0,0,dp(8));setTextIsSelectable(true)})};val scroll=ScrollView(this).apply{addView(body)};val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};box.addView(scroll,LinearLayout.LayoutParams(-1,dp(360)));cardDialog("Inspect · $table",box).show()
    }
    private fun optimizeDatabase(){
        if(queryRunning){showError("Stop the running query before optimizing the database");return}
        if(!::db.isInitialized||!db.isOpen){showError("No active database");return}
        status.text="Optimizing database…";status.setTextColor(Color.rgb(170,165,178))
        val executionDb=if(::db.isInitialized&&db.isOpen)db else {showError("No active database");return}
        Thread{
            val started=System.nanoTime()
            try{
                // ANALYZE refreshes planner statistics; PRAGMA optimize lets SQLite
                // decide which maintenance is actually useful instead of forcing a
                // potentially huge VACUUM on a multi-GB database.
                try{executionDb.execSQL("PRAGMA optimize")}catch(_:Exception){}
                try{executionDb.execSQL("ANALYZE")}catch(_:Exception){}
                runOnUiThread{statusOk("Database optimized · ${elapsed(started)} ms");refreshSchemaPanel()}
            }catch(e:Exception){runOnUiThread{showError("Optimization failed: ${e.message?:"error"}")}}
        }.start()
    }
    private fun databasePerformanceInfo(){
        if(!::db.isInitialized||!db.isOpen){showError("No active database");return}
        val executionDb=if(::db.isInitialized&&db.isOpen)db else {showError("No active database");return}
        Thread{
            try{
                var pageSize=0L;var pageCount=0L;var freelist=0L
                executionDb.rawQuery("PRAGMA page_size",null).use{if(it.moveToFirst())pageSize=it.getLong(0)}
                executionDb.rawQuery("PRAGMA page_count",null).use{if(it.moveToFirst())pageCount=it.getLong(0)}
                executionDb.rawQuery("PRAGMA freelist_count",null).use{if(it.moveToFirst())freelist=it.getLong(0)}
                val tables=executionDb.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata'",null).use{if(it.moveToFirst())it.getInt(0) else 0}
                val size=pageSize*pageCount
                val free=pageSize*freelist
                runOnUiThread{
                    val text="${databaseLabel(executionDb.path)}\n\nTables  $tables\nDatabase  ${humanBytes(size)}\nFree pages  ${humanBytes(free)}\nPage size  ${humanBytes(pageSize)}\n\nLily keeps large results paged and streams imports/exports so database size does not become Android UI memory usage."
                    cardDialog("Database Performance",TextView(this).apply{setText(text);textSize=12f;setTextColor(white);setPadding(0,dp(8),0,dp(8));setTextIsSelectable(true)}).show()
                }
            }catch(e:Exception){runOnUiThread{showError("Could not read database stats: ${e.message?:"error"}")}}
        }.start()
    }
    private fun checkDatabaseIntegrity(){
        if(queryRunning){showError("Stop the running query before checking integrity");return}
        val executionDb=if(::db.isInitialized&&db.isOpen)db else {showError("No active database");return}
        status.text="Checking database integrity…";status.setTextColor(Color.rgb(170,165,178))
        Thread{
            try{
                val result=executionDb.rawQuery("PRAGMA integrity_check",null).use{c->
                    if(c.moveToFirst())c.getString(0) else "unknown"
                }
                runOnUiThread{
                    if(!isFinishing&&!isDestroyed){
                        if(result.equals("ok",true))statusOk("Database integrity check passed") else showError("Integrity check: $result")
                    }
                }
            }catch(e:Exception){runOnUiThread{if(!isFinishing&&!isDestroyed)showError("Integrity check failed: ${e.message?:"error"}")}}
        }.start()
    }
    private fun exportJson(){
        if(queryRunning){showError("Stop the running query before exporting");return}
        if(activeTab<0||tabs[activeTab].isError||tabs[activeTab].query.isNullOrBlank()){showError("Select a successful result query first");return}
        val i=Intent(Intent.ACTION_CREATE_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="application/json";putExtra(Intent.EXTRA_TITLE,"query-result.json")}
        pendingCsv="__STREAM_JSON__";startActivityForResult(i,91)
    }
    private fun sqliteLiteral(c:Cursor,index:Int):String{
        return when(c.getType(index)){
            Cursor.FIELD_TYPE_NULL -> "NULL"
            Cursor.FIELD_TYPE_INTEGER -> c.getLong(index).toString()
            Cursor.FIELD_TYPE_FLOAT -> c.getDouble(index).toString()
            Cursor.FIELD_TYPE_BLOB -> { val bytes=c.getBlob(index); "X'"+bytes.joinToString(""){(it.toInt() and 0xFF).toString(16).padStart(2,'0').uppercase(Locale.US)}+"'" }
            else -> "'${c.getString(index).replace("'","''")}'"
        }
    }
    private fun jsonValue(c:Cursor,index:Int):String{
        return when(c.getType(index)){
            Cursor.FIELD_TYPE_NULL -> "null"
            Cursor.FIELD_TYPE_INTEGER -> c.getLong(index).toString()
            Cursor.FIELD_TYPE_FLOAT -> c.getDouble(index).toString()
            Cursor.FIELD_TYPE_BLOB -> "\"${jsonEscape(c.getBlob(index).joinToString(""){(it.toInt() and 0xFF).toString(16).padStart(2,'0').uppercase(Locale.US)})}\""
            else -> "\"${jsonEscape(c.getString(index))}\""
        }
    }
    private fun streamExportResult(uri:Uri, format:String, query:String){
        val executionDb=if(::db.isInitialized&&db.isOpen)db else null
        if(executionDb==null){showError("No active database");return}
        Thread {
            try{
                contentResolver.openOutputStream(uri)?.use{out->
                    OutputStreamWriter(out,Charsets.UTF_8).buffered().use{w->
                        executionDb.rawQuery(query,null).use{c->
                            if(format=="csv"){
                                for(i in 0 until c.columnCount){if(i>0)w.write(','.code);w.write(csvEscape(c.getColumnName(i)))};w.newLine()
                                while(c.moveToNext()){for(i in 0 until c.columnCount){if(i>0)w.write(','.code);w.write(csvEscape(if(c.isNull(i))"" else c.getString(i)))};w.newLine()}
                            }else{
                                w.write('['.code);var first=true
                                while(c.moveToNext()){if(!first)w.write(",\n");first=false;w.write("  {");for(i in 0 until c.columnCount){if(i>0)w.write(", ");w.write("\"${jsonEscape(c.getColumnName(i))}\":${jsonValue(c,i)}")};w.write("}")};w.newLine();w.write("]\n")
                            }
                        }
                    }
                } ?: throw Exception("Could not open destination")
                runOnUiThread{if(!isFinishing&&!isDestroyed)statusOk(if(format=="csv")"CSV exported from query" else "JSON exported from query")}
            }catch(e:Exception){runOnUiThread{if(!isFinishing&&!isDestroyed)showError("Export failed: ${e.message?:"error"}")}}
        }.start()
    }
    private fun jsonEscape(v:String)=v.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")
    private fun showHistory(){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val top=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        top.addView(TextView(this).apply{text="Recent queries";textSize=11f;setTextColor(Color.rgb(128,132,145));layoutParams=LinearLayout.LayoutParams(0,dp(32),1f)})
        val clear=TextView(this).apply{text="Clear";textSize=12f;setTextColor(blue);gravity=Gravity.CENTER;setPadding(dp(10),0,dp(10),0);isClickable=true;contentDescription="Clear query history"}
        top.addView(clear,LinearLayout.LayoutParams(dp(64),dp(32)))
        box.addView(top)
        val scroll=ScrollView(this).apply{isFillViewport=true;overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS}
        val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        fun render(){
            list.removeAllViews()
            if(history.isEmpty()){list.addView(TextView(this).apply{text="No query history yet.\nRun a query and it will appear here.";textSize=12f;setTextColor(Color.rgb(150,146,158));setPadding(dp(8),dp(16),dp(8),dp(16))})}
            else history.forEachIndexed{index,q->
                val row=TextView(this).apply{text=q.replace(Regex("\\s+")," ").trim();textSize=12f;setTextColor(white);gravity=Gravity.CENTER_VERTICAL;setSingleLine(true);ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(dp(10),0,dp(10),0);background=rounded(Color.rgb(28,30,38),1,Color.rgb(48,51,62),10f);isClickable=true;contentDescription="Restore query ${index+1}"}
                row.setOnClickListener{editor.setText(q);editor.setSelection(editor.length());statusOk("Query restored from history");dialogRef?.dismiss()}
                list.addView(row,LinearLayout.LayoutParams(-1,dp(38)).apply{if(index>0)topMargin=dp(5)})
            }
        }
        scroll.addView(list);box.addView(scroll,LinearLayout.LayoutParams(-1,dp(300)))
        val dialog=cardDialog("Quick History",box);dialogRef=dialog;dialog.setOnDismissListener{if(dialogRef===dialog)dialogRef=null}
        clear.setOnClickListener{
            if(history.isEmpty())return@setOnClickListener
            cardDialog("Clear history?",TextView(this).apply{text="This will remove all saved query history.";textSize=13f;setTextColor(white);setPadding(0,dp(8),0,dp(8))},"Clear"){history.clear();savePrefs();render()}.show()
        }
        render();dialog.show()
    }
    private fun saveSnippetDialog(){
        val box=LinearLayout(this);box.orientation=LinearLayout.VERTICAL
        val hint=TextView(this);hint.text="Give this query a short name.";hint.textSize=12f;hint.setTextColor(Color.rgb(127,131,144));box.addView(hint)
        val input=EditText(this);input.hint="e.g. My JOIN practice";input.setSingleLine(true);input.textSize=14f;input.setTextColor(white);input.setPadding(dp(12),0,dp(12),0);input.background=rounded(Color.rgb(28,30,38),1,Color.rgb(48,51,62),12f);box.addView(input,LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(10)})
        cardDialog("Save snippet",box,"Save"){val name=input.text.toString().trim();if(name.isNotEmpty()){snippets[name]=editor.text.toString();prefs.edit().putString("snippet:$name",editor.text.toString()).apply();statusOk("Snippet saved: $name")}}.show()
        input.requestFocus()
    }
    private fun showSnippetMenu(){
        val names=snippets.keys.toList();val items=if(names.isEmpty())listOf("＋  Save current snippet") else listOf("＋  Save current snippet")+names
        cardListDialog("Snippets",items){i->if(i==0)saveSnippetDialog()else{editor.setText(snippets[names[i-1]]);editor.setSelection(editor.length());statusOk("Snippet loaded")}}
    }
    private fun profileActiveQuery(){
        val executionDb=if(::db.isInitialized&&db.isOpen)db else {showError("No active database");return}
        if(activeTab<0||tabs[activeTab].isError||tabs[activeTab].query.isNullOrBlank()){showError("Select a successful result first");return}
        val q=tabs[activeTab].query!!.trim()
        if(!isQueryStatement(q)){showError("Profile is available for query statements");return}
        Thread{
            val started=System.nanoTime();var rowCount=0;var capped=false
            try{
                val compatible=runCompatibleSql(q)
                if(compatible.startsWith("EXPLAIN",true)){executionDb.rawQuery(compatible,null).use{c->while(c.moveToNext()){rowCount++;if(rowCount>=10000){capped=true;break}}}}
                else{executionDb.rawQuery(compatible,null).use{c->while(c.moveToNext()){rowCount++;if(rowCount>=10000){capped=true;break}}}}
                val ms=elapsed(started)
                val plan=runCatching{planRowsUsing(executionDb,"EXPLAIN QUERY PLAN $compatible")}.getOrElse{emptyList()}
                val scans=plan.filter{it.third.contains("SCAN",true)}
                val searches=plan.filter{it.third.contains("SEARCH",true)}
                val sorts=plan.count{it.third.contains("USE TEMP B-TREE",true)||it.third.contains("SORT",true)}
                runOnUiThread{
                    val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
                    val summary=TextView(this).apply{
                        text="Execution  $ms ms\nRows observed  ${if(capped)"10,000+" else rowCount}\n\n"+
                            when{scans.isNotEmpty()&&searches.isEmpty()->"⚠ Full table scan detected";sorts>0->"⚠ Temporary sort detected";else->"✓ Query executed and plan inspected"}
                        textSize=12.5f;setTextColor(white);setPadding(0,dp(4),0,dp(8))
                    };box.addView(summary)
                    if(scans.isNotEmpty())box.addView(TextView(this).apply{text="Scans  ${scans.size}   ·   Indexed searches  ${searches.size}   ·   Sorts  $sorts";textSize=10.5f;setTextColor(Color.rgb(150,146,158));setPadding(0,0,0,dp(7))})
                    box.addView(buildPlanView(plan),LinearLayout.LayoutParams(-1,dp(205)))
                    val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
                    if(scans.isNotEmpty())actions.addView(Button(ContextThemeWrapper(this,R.style.FlatButton_Accent)).apply{text="Index Advisor";isAllCaps=false;setOnClickListener{dialogRef?.dismiss();showIndexAdvisorForQuery(q)}} ,LinearLayout.LayoutParams(0,dp(36),1f))
                    box.addView(actions,LinearLayout.LayoutParams(-1,dp(36)).apply{topMargin=dp(6)})
                    cardDialog("Query Profile",box).show()
                }
            }catch(e:Exception){runOnUiThread{showError("Profile failed: ${e.message?:"SQL error"}")}}
        }.start()
    }
    private fun showIndexAdvisor(){
        val q=if(activeTab>=0&&!tabs[activeTab].isError)tabs[activeTab].query else editor.text.toString()
        if(q.isNullOrBlank()){showError("Write or run a query first");return}
        showIndexAdvisorForQuery(q.trim())
    }
    private data class IndexSuggestion(val table:String,val column:String,val reason:String)
    private fun showIndexAdvisorForQuery(q:String){
        val executionDb=if(::db.isInitialized&&db.isOpen)db else {showError("No active database");return}
        Thread{
            try{
                val plan=runCatching{val compatible=runCompatibleSql(q);planRowsUsing(executionDb,"EXPLAIN QUERY PLAN $compatible")}.getOrElse{emptyList()}
                val suggestions=mutableListOf<IndexSuggestion>()
                val tables=Regex("(?i)\\b(?:FROM|JOIN|UPDATE|INTO)\\s+([A-Za-z_][A-Za-z0-9_]*)").findAll(q).map{it.groupValues[1]}.distinct().toList()
                val predicates=Regex("(?i)\\b([A-Za-z_][A-Za-z0-9_]*)\\s*(?:=|<|>|<=|>=|LIKE|IN)\\s*").findAll(q).map{it.groupValues[1]}.distinct().toList()
                val joins=Regex("(?i)\\b(?:ON|WHERE)\\s+(?:[A-Za-z_][A-Za-z0-9_]*\\.)?([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?:[A-Za-z_][A-Za-z0-9_]*\\.)?([A-Za-z_][A-Za-z0-9_]*)").findAll(q).flatMap{sequenceOf(it.groupValues[1],it.groupValues[2])}.distinct().toList()
                for(t in tables){
                    val existing=mutableSetOf<String>()
                    val safe=t.replace("'","''")
                    runCatching{executionDb.rawQuery("PRAGMA index_list('$safe')",null).use{c->while(c.moveToNext()){val idx=c.getString(1);executionDb.rawQuery("PRAGMA index_info('${idx.replace("'","''")}')",null).use{ic->while(ic.moveToNext())existing.add(ic.getString(2))}}}}
                    for(col in (predicates+joins).distinct()){
                        if(existing.none{it.equals(col,true)}) suggestions.add(IndexSuggestion(t,col,if(joins.contains(col))"Used in a join predicate" else "Used in a filter predicate"))
                    }
                }
                val fullScans=plan.count{it.third.contains("SCAN",true)}
                runOnUiThread{
                    val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
                    val intro=TextView(this).apply{text=if(suggestions.isEmpty())"No obvious missing single-column index was detected.\n\nLily does not create indexes automatically." else "Potential improvements based on the query plan.\nLily will never create an index without your approval.";textSize=11.5f;setTextColor(Color.rgb(150,146,158));setPadding(0,0,0,dp(8))};box.addView(intro)
                    val scroll=ScrollView(this);val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
                    suggestions.distinctBy{it.table.lowercase()+"."+it.column.lowercase()}.take(12).forEach{sg->
                        val sql="CREATE INDEX idx_${sg.table}_${sg.column} ON \"${sg.table.replace("\"","\"\"")}\"(\"${sg.column.replace("\"","\"\"")}\");"
                        val row=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(8),dp(10),dp(8));background=rounded(Color.rgb(28,30,38),1,Color.rgb(48,51,62),9f)}
                        row.addView(TextView(this).apply{text="${sg.table}.${sg.column}";textSize=12f;setTextColor(white)})
                        row.addView(TextView(this).apply{text="${sg.reason}\n$sql";textSize=10.5f;setTextColor(Color.rgb(170,165,178));setPadding(0,dp(3),0,0);setTextIsSelectable(true)})
                        list.addView(row,LinearLayout.LayoutParams(-1,dp(68)).apply{bottomMargin=dp(5)})
                    }
                    if(fullScans>0)list.addView(TextView(this).apply{text="⚠ $fullScans scan operation(s) appear in the current plan.";textSize=11f;setTextColor(orange);setPadding(dp(8),dp(8),dp(8),dp(8))})
                    if(suggestions.isEmpty()&&fullScans==0)list.addView(TextView(this).apply{text="✓ Plan looks reasonable for the available schema.";textSize=12f;setTextColor(green);setPadding(dp(8),dp(12),dp(8),dp(12))})
                    scroll.addView(list);box.addView(scroll,LinearLayout.LayoutParams(-1,dp(280)));cardDialog("Index Advisor",box).show()
                }
            }catch(e:Exception){runOnUiThread{showError("Index Advisor failed: ${e.message?:"error"}")}}
        }.start()
    }
    private fun tableColumnsFor(table:String, filter:String):List<String>{
        val out=mutableListOf<String>()
        runCatching{
            db.rawQuery("PRAGMA table_info('"+table.replace("'","''")+"')",null).use{c->
                while(c.moveToNext()){
                    val name=c.getString(1)
                    if(filter.isBlank()||name.contains(filter,true))out.add(name)
                }
            }
        }
        return out
    }
    private fun goToDefinition(){
        val pos=editor.selectionStart.coerceAtLeast(0);val before=editor.text.substring(0,pos);val after=editor.text.substring(pos.coerceAtMost(editor.length()));
        val token=(Regex("[A-Za-z_][A-Za-z0-9_]*$").find(before)?.value ?: Regex("^[A-Za-z_][A-Za-z0-9_]*").find(after)?.value).orEmpty()
        if(token.isBlank()){showError("Place the cursor on a table or column name first");return}
        val tables=tableNames();val table=tables.firstOrNull{it.equals(token,true)}
        if(table!=null){inspectTable(table);return}
        val matches=tables.flatMap{t->tableColumnsFor(t,token).map{t to it}}
        if(matches.size==1){editor.insertAtCursor(matches[0].second);statusOk("Definition: ${matches[0].first}.${matches[0].second}");return}
        if(matches.isNotEmpty()){cardListDialog("Column Definition",matches.map{"${it.first}.${it.second}"}){i->insertSql(matches[i].second);statusOk("Inserted ${matches[i].first}.${matches[i].second}")};return}
        showError("No table or column named $token was found")
    }
    private fun searchActiveResult(){
        if(activeTab<0||tabs[activeTab].csv==null){showError("Select a result first");return}
        val source=tabs[activeTab].csv!!
        val input=EditText(this).apply{
            hint="Search rows";setSingleLine(true);setTextColor(white);setHintTextColor(Color.rgb(105,108,119))
            background=rounded(Color.rgb(28,30,38),1,Color.rgb(54,56,66),10f);setPadding(dp(12),0,dp(12),0)
        }
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        box.addView(input,LinearLayout.LayoutParams(-1,dp(38)))
        val count=TextView(this).apply{setTextColor(Color.rgb(150,146,158));textSize=11f;setPadding(0,dp(8),0,dp(4))}
        box.addView(count)
        val scroll=ScrollView(this)
        val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        scroll.addView(list)
        box.addView(scroll,LinearLayout.LayoutParams(-1,dp(220)))
        fun render(){
            list.removeAllViews()
            val needle=input.text.toString().trim()
            val matches=source.drop(1).filter{row->
                needle.isBlank() || row.joinToString(" ").lowercase(Locale.US).indexOf(needle.lowercase(Locale.US))>=0
            }
            count.text="${matches.size} matching row(s)"
            if(matches.isEmpty()){
                list.addView(TextView(this).apply{
                    text="No matching rows";setTextColor(Color.rgb(150,146,158));setPadding(dp(8),dp(14),dp(8),dp(14))
                })
            }else{
                matches.take(500).forEach{row->
                    list.addView(TextView(this).apply{
                        text=row.joinToString("  ·  ");setTextColor(white);textSize=11f;setSingleLine(true)
                        ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(dp(8),dp(7),dp(8),dp(7))
                        setOnClickListener{copyText(row.joinToString("\t"))}
                    })
                }
            }
        }
        input.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,st:Int,c:Int,a:Int){}
            override fun onTextChanged(s:CharSequence?,st:Int,b:Int,c:Int){render()}
            override fun afterTextChanged(e:Editable?){}
        })
        render();cardDialog("Search Result",box).show();input.requestFocus()
    }
    private fun showPractice(){
        val challenges=listOf("Find employees older than 40","Join salary with demographics","Find the highest 3 salaries","Count employees by gender","Show all departments")
        cardListDialog("Practice mode",challenges.mapIndexed{i,s->"${i+1}.  $s"}){which->currentChallenge=which;editor.setText(challengeSql[which]);editor.setSelection(editor.length());statusOk("Challenge loaded — run it, then tap Check")}
    }
    private fun checkPractice(){if(currentChallenge<0){showError("Load a practice challenge first");return};try{val user=editor.text.toString();val expected=challengeSql[currentChallenge];val ur=collectResult(user);val er=collectResult(expected);if(ur==er)statusOk("Practice check passed ✓ — result matches the expected output")else showError("Practice check did not match the expected output. Compare columns, rows, ordering, and filters.")}catch(e:Exception){showError("Practice check error: ${e.message?:"SQL error"}")}}
    private fun collectResult(sql:String):List<List<String>>{val q=splitSql(sql).lastOrNull{isQueryStatement(it)}?:throw Exception("No query found");db.rawQuery(q,null).use{c->val out=mutableListOf<List<String>>();out.add((0 until c.columnCount).map{c.getColumnName(it)});while(c.moveToNext())out.add((0 until c.columnCount).map{if(c.isNull(it))"NULL" else c.getString(it)});return out}}
    private fun planRowsUsing(database:SQLiteDatabase,sql:String):List<Triple<Int,Int,String>>{val out=mutableListOf<Triple<Int,Int,String>>();database.rawQuery(sql,null).use{c->while(c.moveToNext())out.add(Triple(c.getInt(0),c.getInt(1),c.getString(3))) };return out}
    private fun planRows(sql:String):List<Triple<Int,Int,String>>{val out=mutableListOf<Triple<Int,Int,String>>();db.rawQuery(sql,null).use{c->while(c.moveToNext())out.add(Triple(c.getInt(0),c.getInt(1),c.getString(3))) };return out}
    private fun buildPlanView(rows:List<Triple<Int,Int,String>>):View{val t=LinearLayout(this);t.orientation=LinearLayout.VERTICAL;t.setPadding(18,16,18,16);for((id,parent,detail) in rows){val v=TextView(this);v.text=(if(parent==0)"▸ " else "  └─ ")+detail+"  [id=$id parent=$parent]";v.textSize=13f;v.setTextColor(white);v.setPadding(8,8,8,8);t.addView(v)};val s=ScrollView(this);s.addView(t);return s}
    private fun exportDatabaseSql(){
        if(queryRunning){showError("Stop the running query before exporting");return}
        pendingDatabaseSqlExport=true
        val i=Intent(Intent.ACTION_CREATE_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="application/sql";putExtra(Intent.EXTRA_TITLE,"${dbFile.nameWithoutExtension}.sql")}
        startActivityForResult(i,94)
    }
    private fun streamDatabaseSqlExport(uri:Uri){
        val executionDb=if(::db.isInitialized&&db.isOpen)db else {showError("No active database");return}
        Thread{
            try{
                contentResolver.openOutputStream(uri)?.use{raw->
                    OutputStreamWriter(raw,Charsets.UTF_8).buffered().use{w->
                        w.write("PRAGMA foreign_keys=OFF;\nBEGIN TRANSACTION;\n\n")
                        executionDb.rawQuery("SELECT name, sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata' ORDER BY name",null).use{c->
                            while(c.moveToNext()){
                                val name=c.getString(0);val ddl=c.getString(1)
                                if(!ddl.isNullOrBlank())w.write(ddl.trimEnd(';')+";\n")
                                val safe=name.replace("\"","\"\"")
                                executionDb.rawQuery("SELECT * FROM \"$safe\"",null).use{r->
                                    val cols=(0 until r.columnCount).map{r.getColumnName(it)}
                                    val quotedCols=cols.joinToString(", "){"\"${it.replace("\"","\"\"")}\""}
                                    while(r.moveToNext()){
                                        val vals=(0 until r.columnCount).joinToString(", "){i->sqliteLiteral(r,i)}
                                        w.write("INSERT INTO \"$safe\" ($quotedCols) VALUES ($vals);\n")
                                    }
                                }
                                w.write("\n")
                            }
                        }
                        executionDb.rawQuery("SELECT type, name, sql FROM sqlite_master WHERE type IN ('index','trigger','view') AND sql IS NOT NULL ORDER BY CASE type WHEN 'view' THEN 1 WHEN 'index' THEN 2 ELSE 3 END, name",null).use{c->
                            while(c.moveToNext())w.write(c.getString(2).trimEnd(';')+";\n")
                        }
                        w.write("\nCOMMIT;\nPRAGMA foreign_keys=ON;\n")
                    }
                } ?: throw Exception("Could not open output file")
                runOnUiThread{if(!isFinishing&&!isDestroyed)statusOk("Database SQL export complete")}
            }catch(e:Exception){runOnUiThread{if(!isFinishing&&!isDestroyed)showError("Database SQL export failed: ${e.message?:"error"}")}}
        }.start()
    }
    private fun exportDatabase(){
        if(queryRunning){showError("Stop the running query before exporting");return}
        val source=dbFile
        pendingDatabaseExportPath=source.path
        val i=Intent(Intent.ACTION_CREATE_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="application/octet-stream";putExtra(Intent.EXTRA_TITLE,"ProjectLily-${source.name}")}
        startActivityForResult(i,88)
    }
    private fun csvEscape(s:String)=if(s.contains(',')||s.contains('"')||s.contains('\n'))"\"${s.replace("\"","\"\"")}\"" else s
    internal fun onEditorSelectionChanged(){ updateCurrentStatementIndicator() }
    private fun dp(value:Int):Int=(value*resources.displayMetrics.density).roundToInt()
    private fun rounded(fill:Int, stroke:Int, strokeColor:Int, radius:Float):android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply{
            setColor(fill)
            if(stroke>0)setStroke(dp(stroke),strokeColor)
            cornerRadius=dp(radius.toInt()).toFloat()
        }
    private fun animatePopupIn(view:View){view.alpha=0f;view.scaleX=.97f;view.scaleY=.97f;view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(145).setInterpolator(android.view.animation.DecelerateInterpolator()).start()}
    internal fun showEditorContextMenu(anchor:View,x:Float,y:Float){
        val hasSelection=editor.selectionStart!=editor.selectionEnd
        val popup=PopupWindow(this)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(4),dp(4),dp(4),dp(4));background=rounded(Color.rgb(29,30,36),1,Color.rgb(78,79,87),11f);elevation=dp(9).toFloat()}
        fun item(icon:String,label:String,enabled:Boolean=true,action:()->Unit){
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(6),0,dp(8),0);isClickable=enabled;alpha=if(enabled)1f else .42f;background=rounded(Color.TRANSPARENT,0,Color.TRANSPARENT,7f)}
            row.addView(TextView(this).apply{text=icon;textSize=10.5f;setTextColor(Color.rgb(218,218,223));gravity=Gravity.CENTER;layoutParams=LinearLayout.LayoutParams(dp(21),dp(27))})
            row.addView(TextView(this).apply{text=label;textSize=11f;setTextColor(Color.rgb(236,235,239));gravity=Gravity.CENTER_VERTICAL;layoutParams=LinearLayout.LayoutParams(-2,dp(27))})
            row.setOnHoverListener{v,e->if(e.action==MotionEvent.ACTION_HOVER_ENTER)v.setBackgroundColor(Color.rgb(53,54,62));else if(e.action==MotionEvent.ACTION_HOVER_EXIT)v.setBackgroundColor(Color.TRANSPARENT);false}
            row.setOnClickListener{if(enabled){popup.dismiss();action()}};root.addView(row)
        }
        fun divider(){root.addView(View(this).apply{setBackgroundColor(Color.rgb(65,66,73))},LinearLayout.LayoutParams(-1,1).apply{topMargin=dp(2);bottomMargin=dp(2)})}
        item("↶","Undo"){editor.performLilyTextAction(android.R.id.undo)};divider();item("✂","Cut",hasSelection){editor.performLilyTextAction(android.R.id.cut)};item("▣","Copy",hasSelection){editor.performLilyTextAction(android.R.id.copy)};item("▢","Paste"){editor.performLilyTextAction(android.R.id.paste)};item("⌗","Select all"){editor.performLilyTextAction(android.R.id.selectAll)};divider();item("✦","Auto-fill"){if(android.os.Build.VERSION.SDK_INT>=26)editor.performLilyTextAction(android.R.id.autofill)};item("↗","Share"){val text=if(hasSelection)editor.text.substring(minOf(editor.selectionStart,editor.selectionEnd),maxOf(editor.selectionStart,editor.selectionEnd)) else editor.text.toString();val share=Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,text)};startActivity(Intent.createChooser(share,"Share SQL"))}
        popup.contentView=root;popup.width=dp(112);popup.height=dp(210);popup.isFocusable=true;popup.isOutsideTouchable=true;popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));val loc=IntArray(2);anchor.getLocationOnScreen(loc);val screenW=resources.displayMetrics.widthPixels;val screenH=resources.displayMetrics.heightPixels;val px=(loc[0]+x).toInt();val py=(loc[1]+y).toInt();val left=px.coerceIn(dp(8),screenW-popup.width-dp(8));val top=(if(py+popup.height+dp(5)<screenH)py+dp(5) else py-popup.height-dp(5)).coerceIn(dp(8),screenH-popup.height-dp(8));popup.showAtLocation(anchor,Gravity.TOP or Gravity.START,left,top);animatePopupIn(root)
    }
    private fun installMacPointerIcons(){
        if(android.os.Build.VERSION.SDK_INT<24)return
        val arrow=android.view.PointerIcon.getSystemIcon(this,android.view.PointerIcon.TYPE_ARROW)
        val text=android.view.PointerIcon.getSystemIcon(this,android.view.PointerIcon.TYPE_TEXT)
        fun apply(v:View){
            if(v===editor)v.pointerIcon=text else v.pointerIcon=arrow
            if(v is ViewGroup)for(i in 0 until v.childCount)apply(v.getChildAt(i))
        }
        apply(window.decorView)
    }
    private fun insertSql(text:String){
        val start=editor.selectionStart.coerceAtLeast(0)
        val end=editor.selectionEnd.coerceAtLeast(0)
        val a=minOf(start,end).coerceIn(0,editor.length())
        val b=maxOf(start,end).coerceIn(a,editor.length())
        try{
            editor.text.replace(a,b,text)
            editor.setSelection((a+text.length).coerceAtMost(editor.length()))
            editor.requestFocus()
            highlight()
        }catch(e:Exception){showError("Could not insert into editor")}
    }
    private fun cardDialog(titleText:String, content:View, positiveText:String?=null, positiveAction:(()->Unit)?=null):AlertDialog{
        val outer=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(16),dp(12),dp(16),dp(14))
            background=rounded(Color.rgb(22,24,31),1,Color.rgb(58,60,70),15f)
        }
        val titleRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val title=TextView(this).apply{text=titleText;textSize=15f;setTextColor(white);setTypeface(null,android.graphics.Typeface.BOLD);gravity=Gravity.CENTER_VERTICAL;ellipsize=android.text.TextUtils.TruncateAt.END;maxLines=1}
        titleRow.addView(title,LinearLayout.LayoutParams(0,dp(32),1f))
        val close=TextView(this).apply{text="×";textSize=19f;setTextColor(Color.rgb(154,157,164));gravity=Gravity.CENTER;isClickable=true;contentDescription="Close";background=rounded(Color.rgb(30,32,39),0,Color.TRANSPARENT,16f);setOnClickListener{}}
        titleRow.addView(close,LinearLayout.LayoutParams(dp(28),dp(28)))
        outer.addView(titleRow)
        val divider=View(this).apply{setBackgroundColor(Color.rgb(42,44,53))}
        outer.addView(divider,LinearLayout.LayoutParams(-1,dp(1)).apply{topMargin=dp(8);bottomMargin=dp(12)})
        outer.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        val dialog=AlertDialog.Builder(this).setView(outer).create()
        close.setOnClickListener{dialog.dismiss()}
        positiveText?.let{label->
            val action=Button(ContextThemeWrapper(this,R.style.FlatButton_Accent)).apply{text=label;isAllCaps=false;setOnClickListener{positiveAction?.invoke();dialog.dismiss()}}
            outer.addView(action,LinearLayout.LayoutParams(-1,dp(38)).apply{topMargin=dp(10)})
        }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnShowListener{dialog.window?.let{w->w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));w.setDimAmount(.34f);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);val maxW=dp(430);val screenW=resources.displayMetrics.widthPixels;w.setLayout(minOf(maxW,screenW-dp(28)),WindowManager.LayoutParams.WRAP_CONTENT)};outer.alpha=0f;outer.scaleX=.97f;outer.scaleY=.97f;outer.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).setInterpolator(android.view.animation.DecelerateInterpolator()).start()}
        return dialog
    }
    private fun cardListDialog(title:String, items:List<String>, onPick:(Int)->Unit){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val scroll=ScrollView(this).apply{isFillViewport=true;overScrollMode=View.OVER_SCROLL_NEVER}
        val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        if(items.isEmpty()){
            list.addView(TextView(this).apply{text="Nothing here yet.";textSize=12f;setTextColor(Color.rgb(150,146,158));setPadding(dp(8),dp(14),dp(8),dp(14))})
        }else{
            items.forEachIndexed{index,label->
                val row=TextView(this).apply{text=label;textSize=12.5f;setTextColor(white);gravity=Gravity.CENTER_VERTICAL;setPadding(dp(10),0,dp(10),0);background=rounded(Color.TRANSPARENT,0,Color.TRANSPARENT,8f);isClickable=true;ellipsize=android.text.TextUtils.TruncateAt.END;maxLines=1}
                row.setOnClickListener{onPick(index);dialogRef?.dismiss()}
                row.setOnHoverListener{v,e->when(e.actionMasked){MotionEvent.ACTION_HOVER_ENTER->v.background=rounded(Color.rgb(42,44,52),0,Color.TRANSPARENT,8f);MotionEvent.ACTION_HOVER_EXIT->v.background=rounded(Color.TRANSPARENT,0,Color.TRANSPARENT,8f)};false}
                list.addView(row,LinearLayout.LayoutParams(-1,dp(34)).apply{if(index>0)topMargin=dp(1)})
            }
        }
        scroll.addView(list);box.addView(scroll,LinearLayout.LayoutParams(-1,dp(310)))
        val dialog=cardDialog(title,box)
        dialogRef=dialog
        dialog.setOnDismissListener{if(dialogRef===dialog)dialogRef=null}
        dialog.show()
    }
    private var dialogRef:AlertDialog?=null
    private fun EditText.insertAtCursor(value:String){val p=selectionStart.coerceAtLeast(0);text.replace(p,p,value);setSelection((p+value.length).coerceAtMost(length()))}
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        if(queryFileSaveController.handleActivityResult(requestCode,resultCode,data?.data)) return
        if(resultExportController.handleActivityResult(requestCode,resultCode,data?.data)) return
        if(requestCode==77 && resultCode==RESULT_OK){
            val uri=data?.data
            if(uri!=null){
                runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
                val name=runCatching{
                    contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->
                        if(c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) else null
                    }
                }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank{"import"}
                startImport(uri,name)
            }else{
                statusOk("Import cancelled")
            }
            return
        }
        super.onActivityResult(requestCode,resultCode,data)
    }
    override fun onDestroy(){importCancelled=true;queryCancelled=true;activeQueryCancellation?.cancel();highlightRunnable?.let{mainHandler.removeCallbacks(it)};mainHandler.removeCallbacksAndMessages(null);backgroundExecutor.shutdownNow();if(::db.isInitialized&&db.isOpen)db.close();super.onDestroy()}
    override fun onPause(){persistCurrentScript();saveScriptTabs();super.onPause()}
}
