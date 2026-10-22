import java.io.*;import java.lang.reflect.*;import java.nio.file.*;import java.util.*;import org.eclipse.mat.snapshot.*;import org.eclipse.mat.snapshot.model.*;import org.eclipse.mat.util.VoidProgressListener;import shapes.mat.MatExtract;
/** Recompute the retained id array of the configuration roots and cache it:
 *  /tmp/retained-ids.bin = int32 count + int32[] sorted MAT ids (class objects excluded),
 *  i.e. dense GRPH id i <-> MAT id ids[i]. */
public class RetainedIds {
 public static void main(String[] a)throws Exception{
  Method open=MatExtract.class.getDeclaredMethod("open",String.class,boolean.class);open.setAccessible(true);ISnapshot s=(ISnapshot)open.invoke(MatExtract.INSTANCE,a[0],true);
  try{
   ArrayList<Integer> rootsList=new ArrayList<>();
   for(IClass cl:s.getClassesByName(a[1],false))for(int id:cl.getObjectIds())rootsList.add(id);
   int[] roots=rootsList.stream().mapToInt(Integer::intValue).toArray();
   System.out.println("roots="+roots.length);
   int[] retained=s.getRetainedSet(roots,new VoidProgressListener());Arrays.sort(retained);
   System.out.println("retained="+retained.length);
   HashSet<Integer> extraClasses=new HashSet<>();
   for(IClass cl:s.getClassesByName("java.lang.Class",false))for(int id:cl.getObjectIds())if(!s.isClass(id))extraClasses.add(id);
   try(DataOutputStream d=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(a[2])))){
    int n=0;for(int id:retained)if(!(s.isClass(id)||extraClasses.contains(id)))n++;
    d.writeInt(n);
    for(int id:retained)if(!(s.isClass(id)||extraClasses.contains(id)))d.writeInt(id);
    System.out.println("wrote n="+n);
   }
  }finally{s.dispose();}
 }
}
