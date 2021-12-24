
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


    public static void main(String[] args){

        Set<String> setOfEvents = new HashSet<>(Arrays.asList("onClick", "onLongClick", "onFocusChange", "onKey",
                "onTouch", "onCreateContextMenu"));

        if(System.getenv().containsKey("ANDROID_HOME"))
            androidJar = System.getenv("ANDROID_HOME")+ File.separator+"platforms";
        final File[] files = (new File(outputPath)).listFiles();
        if (files != null && files.length > 0) {
            Arrays.asList(files).forEach(File::delete);
        }
        InstrumentUtil.setupSoot(androidJar, apkPath, outputPath);
        PackManager.v().getPack("jtp").add(new Transform("jtp.myLogger", new BodyTransformer() {
            @Override
            protected void internalTransform(Body b, String phaseName, Map<String, String> options) {
                final PatchingChain<Unit> units = b.getUnits();
                for(Iterator<Unit> iter = units.snapshotIterator(); iter.hasNext();) {
                    final Unit unit = iter.next();
                    unit.apply(new AbstractStmtSwitch() {

                        public void caseInvokeStmt(InvokeStmt stmt) {
                            InvokeExpr invokeExpr = stmt.getInvokeExpr();
                            String methodName = invokeExpr.getMethod().getName();
                            if(setOfEvents.contains(methodName)) {
                                long startTime = System.currentTimeMillis();
                                long timeSpent = 0;
                                if(unit instanceof ReturnStmt || unit instanceof ReturnVoidStmt) {
                                    timeSpent = System.currentTimeMillis() - startTime;
                                    addLog(b, timeSpent);

                                } else if(unit instanceof DefinitionStmt || unit instanceof InvokeStmt) {
                                    startTime = System.currentTimeMillis();
                                }

                                b.validate();
                            }
                        }

                    });
                }

            }
        }));
        PackManager.v().runPacks();
        PackManager.v().writeOutput();

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
        Value printlnParamter = StringConstant.v(content);
        InvokeStmt printlnMethodCallStmt = Jimple.v().newInvokeStmt(Jimple.v().newVirtualInvokeExpr(psLocal, printlnMethod.makeRef(), printlnParamter));
        generatedUnits.add(printlnMethodCallStmt);
        units.insertBefore(generatedUnits, body.getFirstNonIdentityStmt());
    }

}
