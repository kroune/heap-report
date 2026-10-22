import java.io.*;import java.lang.reflect.*;import java.util.*;import org.eclipse.mat.snapshot.*;import org.eclipse.mat.snapshot.model.*;import shapes.mat.MatExtract;
/** For every root configuration: read description; if it has an inbound referrer other than
 *  the configuration itself, report (config mat id, identityPath, referrer class). */
public class SharedDesc {
 static String string(IObject str)throws Exception{
  if(str==null)return "null";IObject val=(IObject)str.resolveValue("value");Object bytes=((IPrimitiveArray)val).getValueArray();
  if(bytes instanceof char[])return new String((char[])bytes);
  Object coder=str.resolveValue("coder");return new String((byte[])bytes,((Number)coder).intValue()==0?java.nio.charset.StandardCharsets.ISO_8859_1:(java.nio.ByteOrder.nativeOrder()==java.nio.ByteOrder.LITTLE_ENDIAN?java.nio.charset.StandardCharsets.UTF_16LE:java.nio.charset.StandardCharsets.UTF_16BE));
 }
 static String path(IObject p)throws Exception{
  if(p==null)return "null";
  Object segs=p.resolveValue("segments");
  if(segs instanceof IObjectArray){long[] addrs=((IObjectArray)segs).getReferenceArray();StringBuilder b=new StringBuilder();
   for(long addr:addrs){if(addr==0){b.append(":null");continue;}int id=p.getSnapshot().mapAddressToId(addr);b.append(':').append(string(p.getSnapshot().getObject(id)));}return b.toString();}
  return string((IObject)p.resolveValue("fullPath"));
 }
 public static void main(String[] a)throws Exception{
  Method open=MatExtract.class.getDeclaredMethod("open",String.class,boolean.class);open.setAccessible(true);ISnapshot s=(ISnapshot)open.invoke(MatExtract.INSTANCE,a[0],true);
  try{
   TreeSet<Integer> roots=new TreeSet<>();for(IClass cl:s.getClassesByName(a[1],false))for(int id:cl.getObjectIds())roots.add(id);
   Map<String,Integer> hist=new TreeMap<>();
   Map<String,Integer> byModule=new TreeMap<>();
   int affected=0, withDesc=0;
   PrintWriter w=new PrintWriter(a[2]);
   for(int id:roots){
    IObject obj=s.getObject(id);
    IObject desc=(IObject)obj.resolveValue("description");
    if(desc==null)continue;
    withDesc++;
    int[] in=s.getInboundRefererIds(desc.getObjectId());
    String other=null;
    for(int r:in){if(r!=id){other=s.getObject(r).getClazz().getName();break;}}
    if(other!=null){
     affected++;
     String ip=path((IObject)obj.resolveValue("identityPath"));
     String mod=ip.substring(0,ip.lastIndexOf(':'));
     byModule.merge(mod,1,Integer::sum);
     hist.merge(other,1,Integer::sum);
     w.println(id+"\t"+ip+"\t"+other);
    }
   }
   w.close();
   System.out.println("roots="+roots.size()+" withDesc="+withDesc+" affected="+affected);
   System.out.println("referrer classes: "+hist);
   System.out.println("by module ("+byModule.size()+"):");
   byModule.forEach((k,v)->System.out.println("  "+v+"\t"+k));
  }finally{s.dispose();}
 }
}
