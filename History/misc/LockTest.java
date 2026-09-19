import java.io.*;
import java.nio.channels.*;

public class LockTest {
    public static void main(String[] args) throws Exception {
        String path = args[0];
        File f = new File(path);
        f.getParentFile().mkdirs();
        RandomAccessFile raf = new RandomAccessFile(f, "rw");
        FileChannel ch = raf.getChannel();
        try {
            FileLock lock = ch.tryLock();
            System.out.println("tryLock result: " + (lock != null));
            if (lock != null) lock.release();
        } catch (Exception e) {
            System.out.println("tryLock exception: " + e.getClass().getName() + ": " + e.getMessage());
        }
        ch.close();
        raf.close();
        // 普通写文件测试
        try {
            File f2 = new File(path + ".txt");
            FileWriter w = new FileWriter(f2);
            w.write("test");
            w.close();
            System.out.println("plain write: OK");
        } catch (Exception e) {
            System.out.println("plain write exception: " + e.getMessage());
        }
    }
}
