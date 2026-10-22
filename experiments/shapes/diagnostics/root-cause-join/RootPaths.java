import java.io.*;import java.lang.reflect.*;import java.nio.file.*;import java.util.*;import org.eclipse.mat.snapshot.*;import org.eclipse.mat.snapshot.model.*;import shapes.mat.MatExtract;
/** For every root configuration: extract identityPath (segments joined) + project dir identity.
 *  identityPath = ":api:f0-api:releaseCompileClasspath" -> module path is all but last segment. */
public class RootPaths {
 static String string(IObject str)throws Exception{
  if(str==null)return "null";IObject val=(IObject)str.resolveValue("value");Object bytes=((IPrimitiveArray)val).getValueArray();
  if(bytes instanceof char[])return new String((char[])bytes);
  Object coder=str.resolveValue("coder");return new String((byte[])bytes,((Number)coder).intValue()==0?java.nio.charset.StandardCharsets.ISO_8859_1:(java.nio.ByteOrder.nativeOrder()==java.nio.ByteOrder.LITTLE_ENDIAN?java.nio.charset.StandardCharsets.UTF_16LE:java.nio.charset.StandardCharsets.UTF_16BE));
 }
 static String path(IObject p)throws Exception{
  if(p==null)return "null";
  Object segs=p.resolveValue("segments");
  if(segs instanceof IObjectArray){long[] addrs=((IObjectArray)segs).getReferenceArray();ISnapshot sn=p.getSnapshot();StringBuilder b=new StringBuilder();for(long addr:addrs){if(addr==0){b.append(":null");continue;}b.append(':').append(string(sn.getObject(sn.mapAddressToId(addr))));}return b.toString();}
  // fallback: fullPath
  return string((IObject)p.resolveValue("fullPath"));
 }
 public static void main(String[] a)throws Exception{
  Method open=MatExtract.class.getDeclaredMethod("open",String.class,boolean.class);open.setAccessible(true);ISnapshot s=(ISnapshot)open.invoke(MatExtract.INSTANCE,a[0],true);
  try{
   TreeSet<Integer> roots=new TreeSet<>();for(IClass cl:s.getClassesByName(a[1],false))for(int id:cl.getObjectIds())roots.add(id);
   try(PrintWriter w=new PrintWriter(a[2])){
    w.println("mat_id\tidentityPath");int n=0;
    for(int id:roots){IObject obj=s.getObject(id);w.println(id+"\t"+path((IObject)obj.resolveValue("identityPath")));
     if(++n%50000==0)System.out.println("READ "+n+"/"+roots.size());
    }
   }
   System.out.println("DONE paths="+roots.size());
  }finally{s.dispose();}
 }
}
