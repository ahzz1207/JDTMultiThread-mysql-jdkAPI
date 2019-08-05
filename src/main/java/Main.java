import org.eclipse.jdt.core.dom.CompilationUnit;
import java.io.*;
import java.lang.*;
import java.sql.*;
import java.util.*;
import java.util.Date;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.mysql.cj.jdbc.Driver;

public class Main {
    public static String dir = "src/main/resources/project";
    public static ArrayList<String> listProj = new ArrayList<>();
    public static AtomicInteger succesMeth = new AtomicInteger();
    public static AtomicInteger failedMeth = new AtomicInteger();
    public static AtomicInteger repos = new AtomicInteger();
    public static HashMap<String, Integer> selected = new HashMap<>();
    public static ThreadPoolExecutor poolExecutor = new ThreadPoolExecutor(200, 300, 5000,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(50000));
    private static volatile int index = 0;

    public static void listClasses(Connection conn, HashMap<String, Integer> selected) throws IOException {
        // 一级目录是username
        // 二级目录是projname
        // 不同username下的projname可能会重复 username-projname
        //存储已经处理过的项目
        HashSet<String> dealtProjects = new HashSet<>();
        String sql = "SELECT reponame FROM `repos_deal`";
        try {
            PreparedStatement stmt = conn.prepareStatement(sql);
            ResultSet result = stmt.executeQuery();
            while (result.next()) {
                dealtProjects.add(result.getString("reponame"));
            }
            System.out.println("Already handled repos is: " + dealtProjects.size());
        } catch (SQLException e) {
            e.printStackTrace();
        }

        //得到需要处理的地址
        HashSet<File> userNameFiles = new HashSet<>();
        FileReader projsPath = new FileReader("addrlist.txt");
        BufferedReader in = new BufferedReader(projsPath);
        String str;
        while ((str = in.readLine()) != null) {
            userNameFiles.add(new File(str));
        }
        System.out.println("will produce project is " + userNameFiles.size());

        for (File proj : userNameFiles){
            String[] temps = proj.getPath().split(File.separator);
            String name = temps[temps.length-2] + "$$%" + temps[temps.length-1];
            if (!dealtProjects.contains(name) && selected.containsKey(name)) {
                listProj.add(proj.getPath());
            }
        }

        System.out.println("The files number is: " + listProj.size());
    }

    public static HashMap<String, Integer> selectFile(Connection connection){
        Connection conn = connection;
        PreparedStatement stmt = null;
        HashMap<String, Integer> selected = new HashMap<>();
        try {
            String sql = "select id, reponame from star5";
            stmt = conn.prepareStatement(sql);
            ResultSet result = stmt.executeQuery();
            while (result.next()){
                selected.put(result.getString(2), result.getInt(1));
            }
            stmt.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return selected;
    }
    public static void main(String[] args) throws IOException {
        // 链接数据库
        Connection conn = null;
        String driver = "com.mysql.cj.jdbc.Driver";
        String url = "jdbc:mysql://10.131.252.198:3306/repos?serverTimezone=UTC";
        String user = "root";
        String password = "17210240114";
        String sql = "insert into reposfile (`methName`, `tokens`, `comments`, `rawcode`, `apiseq`, `ast`, `newapiseq`) values (?,?,?,?,?,?,?)" ;
        String logsql = "insert into repos_deal (`id`, `reponame`) values (?,?)";
        try {
            Class.forName(driver);
            conn = DriverManager.getConnection(url, user, password);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        selected = selectFile(conn);
        listClasses(conn, selected);
        for (String path :listProj
             ) {
            poolExecutor.execute(new MultiVisitor(path, conn, sql, logsql, selected));
        }
        long count = poolExecutor.getCompletedTaskCount();
        Date date = new Date();
        long time;
        long starttime = date.getTime();
        while (count < listProj.size()){
            count = poolExecutor.getCompletedTaskCount();
            time = date.getTime() - starttime;
            if (time > 30 * 1000){
                System.out.println("Already handled file is : " + count);
                starttime = date.getTime();
            }
        }
        return;
    }
    }

class MultiVisitor implements Runnable {
    private Connection conn;
    private String sql;
    private String logsql;
    private String sqlNoComments;
    private HashMap<String, Integer> selected;
    private String path;

    MultiVisitor(String path, Connection conn, String sql, String logsql, HashMap<String, Integer> selected) {
        this.path = path;
        this.conn = conn;
        this.sql = sql;
        this.logsql = logsql;
        this.selected = selected;
    }

    @Override
    public void run() {
        PreparedStatement stmt = null;
        long startTime = new Date().getTime();
        String[] info = path.split(File.separator);
        String reponame = info[info.length - 2] + "$$%" + info[info.length - 1];
        new DirExplorer((level, path, file) -> path.endsWith(".java"), (level, path, file) -> {
            // System.out.println(file.getPath());
            try {
                CompilationUnit cu = JdtAstUtil.getCompilationUnit(file.getPath());
                MyVisitor myVisitor = new MyVisitor(this.conn, this.sql, this.sqlNoComments);
                if (cu != null ) {
                    cu.accept(myVisitor);
                }
            } catch (Exception e) {
                System.err.println(path);
            }
        }).explore(new File(path));
        // projPath 是绝对路径
        long endTIme = new Date().getTime();
        // 记录已完成的repository
        // 记录已处理的项目
        try {
            stmt = conn.prepareStatement(logsql);
            stmt.setInt(1, selected.get(reponame));
            stmt.setString(2, reponame);
            stmt.execute();
            conn.commit();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        System.out.println(info[info.length - 2] + "-" + info[info.length - 1] + " consume time:" + (endTIme - startTime));

        try {
            stmt.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}

