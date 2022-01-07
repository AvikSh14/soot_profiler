
import org.apache.maven.settings.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;

import java.io.File;
import java.util.*;

import soot.jimple.infoflow.android.data.AndroidMethod;

public class AndroidLogger {

    private final static String USER_HOME = System.getProperty("user.home");
    private static String androidJar = USER_HOME + "/Library/Android/sdk/platforms";
    private static String dirForAPK = "input";
    static String androidDemoPath = System.getProperty("user.dir") + File.separator + dirForAPK;
    static String apkPath = androidDemoPath + File.separator + "/TestCICD.apk";
    static String outputPath = androidDemoPath + File.separator + "/Instrumented";

    private static long curTime;
    public static Logger log = LoggerFactory.getLogger(Main.class);
    public static long startTime = 0;
    private static String eventStartTime = "eventStart";
    private static String eventEndTime = "eventEnd";
    private static String eventDuration = "duration";


    public static void main(String[] args) {

        Set<String> setOfEvents = new HashSet<>(Arrays.asList("onClick", "onLongClick", "onFocusChange", "onKey",
                "onTouch", "onCreateContextMenu"));

        if (System.getenv().containsKey("ANDROID_HOME"))
            androidJar = System.getenv("ANDROID_HOME") + File.separator + "platforms";
        final File[] files = (new File(outputPath)).listFiles();
        if (files != null && files.length > 0) {
            Arrays.asList(files).forEach(File::delete);
        }
        InstrumentUtil.setupSoot(androidJar, apkPath, outputPath);
        PackManager.v().getPack("jtp").add(new Transform("jtp.myLogger", new BodyTransformer() {
            @Override
            protected void internalTransform(Body body, String phaseName, Map<String, String> options) {
                final PatchingChain<Unit> units = body.getUnits();
                for (Iterator<Unit> iter = units.snapshotIterator(); iter.hasNext(); ) {
                    final Unit unit = iter.next();
                    unit.apply(new AbstractStmtSwitch() {

                        public void caseInvokeStmt(InvokeStmt stmt) {
                            InvokeExpr invokeExpr = stmt.getInvokeExpr();
                            String methodName = invokeExpr.getMethod().getName();
                            if (setOfEvents.contains(methodName)) {
                                if (unit instanceof DefinitionStmt || unit instanceof InvokeStmt) {
                                    //insert event start time into code
                                    Local localEventStart = InstrumentUtil.generateNewLocal(body, LongType.v());
                                    SootMethod currentTimeMillis = Scene.v().getMethod("<java.lang.System: long currentTimeMillis()>");
                                    StaticInvokeExpr timeInvoke = Jimple.v().newStaticInvokeExpr(currentTimeMillis.makeRef());
                                    AssignStmt timeInitalize = Jimple.v().newAssignStmt(localEventStart, timeInvoke);
                                    units.insertBefore(timeInitalize, unit);

                                }
                                if (unit instanceof ReturnStmt || unit instanceof ReturnVoidStmt) {
                                    Local localEventStart = getLocal(body, eventStartTime);

                                    //insert event end time into code
                                    Local localEventEnd = InstrumentUtil.generateNewLocal(body, LongType.v());
                                    SootMethod currentTimeMillis = Scene.v().getMethod("<java.lang.System: long currentTimeMillis()>");
                                    StaticInvokeExpr timeInvoke = Jimple.v().newStaticInvokeExpr(currentTimeMillis.makeRef());
                                    AssignStmt timeInitalize = Jimple.v().newAssignStmt(localEventEnd, timeInvoke);
                                    units.insertBefore(timeInitalize, unit);

                                    //insert event duration into code
                                    if (localEventEnd!=null) {
                                        Local duration = InstrumentUtil.generateNewLocal(body, LongType.v());
                                        SubExpr subExpr = Jimple.v().newSubExpr(localEventEnd, localEventStart);
                                        AssignStmt durationAssignStmt = Jimple.v().newAssignStmt(duration, subExpr);
                                        units.insertBefore(durationAssignStmt, unit);
                                    }
                                }

                                body.validate();
                            }
                        }

                    });
                }

            }
        }));
        PackManager.v().runPacks();
        PackManager.v().writeOutput();

    }

    private static Local getLocal(Body body, String fieldName) {
        for (Local local : body.getLocals()) {
            if (local.getName().equals(fieldName)) {
                return local;
            }
        }
        return null;
    }

    private static void addLog(Body b, long timeSpent) {
        JimpleBody body = (JimpleBody) b;
        UnitPatchingChain units = b.getUnits();
        List<Unit> generatedUnits = new ArrayList<>();
        String content = "Time spent : " + timeSpent;
        Local psLocal = InstrumentUtil.generateNewLocal(body, RefType.v("java.io.PrintStream"));
        SootField sysOutField = Scene.v().getField("<java.lang.System: java.io.PrintStream out>");
        AssignStmt sysOutAssignStmt = Jimple.v().newAssignStmt(psLocal, Jimple.v().newStaticFieldRef(sysOutField.makeRef()));
        generatedUnits.add(sysOutAssignStmt);
        SootMethod printlnMethod = Scene.v().grabMethod("<java.io.PrintStream: void println(java.lang.String)>");
        Value printlnParameter = StringConstant.v(content);
        InvokeStmt printlnMethodCallStmt = Jimple.v().newInvokeStmt(Jimple.v().newVirtualInvokeExpr(psLocal, printlnMethod.makeRef(), printlnParameter));
        generatedUnits.add(printlnMethodCallStmt);
        units.insertBefore(generatedUnits, body.getFirstNonIdentityStmt());
    }

}
