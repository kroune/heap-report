import java.io.*;import java.lang.reflect.*;import java.nio.file.*;import java.util.*;import org.eclipse.mat.snapshot.*;import org.eclipse.mat.snapshot.model.*;import shapes.*;import shapes.mat.MatExtract;
/** For a root dense id: compute its raw-GRPH SCC (iterative Tarjan), list member classes,
 *  list every outbound edge member->java.lang.String (or any target class filter),
 *  map objects to MAT ids via /tmp/retained-ids.bin and read String contents from the dump. */
public class SccInspect {
 static int[] ids; // dense -> MAT id
 static int denseOfMat(int mat){ return Arrays.binarySearch(ids, mat); }
 static String string(IObject str)throws Exception{
  if(str==null)return "null";IObject val=(IObject)str.resolveValue("value");Object bytes=((IPrimitiveArray)val).getValueArray();
  if(bytes instanceof char[])return new String((char[])bytes);
  Object coder=str.resolveValue("coder");return new String((byte[])bytes,((Number)coder).intValue()==0?java.nio.charset.StandardCharsets.ISO_8859_1:(java.nio.ByteOrder.nativeOrder()==java.nio.ByteOrder.LITTLE_ENDIAN?java.nio.charset.StandardCharsets.UTF_16LE:java.nio.charset.StandardCharsets.UTF_16BE));
 }
 public static void main(String[] a)throws Exception{
  ObjectGraph g=ObjectGraph.Companion.loadGrph(Path.of(a[0]));
  int n=g.getSize();char[] cls=g.getClassIdx();int[] off=g.getRefOffsets();int[] refs=g.getRefs();String[] names=g.getClassNames();
  try(DataInputStream d=new DataInputStream(new BufferedInputStream(new FileInputStream(a[1])))){
   int cnt=d.readInt();ids=new int[cnt];for(int i=0;i<cnt;i++)ids[i]=d.readInt();
  }
  System.out.println("graph="+n+" ids="+ids.length);
  // iterative Tarjan
  int[] index=new int[n];Arrays.fill(index,-1);int[] low=new int[n];int[] comp=new int[n];Arrays.fill(comp,-1);
  int[] stack=new int[n];int sp=0;boolean[] onstack=new boolean[n];int[] it=new int[n];int idx=0;int ncomp=0;
  int[] call=new int[n];
  for(int s=0;s<n;s++){
   if(index[s]>=0)continue;
   int csp=0;call[csp++]=s;
   while(csp>0){
    int v=call[csp-1];
    if(index[v]<0){index[v]=low[v]=idx++;stack[sp++]=v;onstack[v]=true;it[v]=off[v];}
    boolean recursed=false;
    for(int j=it[v];j<off[v+1];j++){
     int w=refs[j];it[v]=j+1;
     if(index[w]<0){call[csp++]=w;recursed=true;break;}
     else if(onstack[w])low[v]=Math.min(low[v],index[w]);
    }
    if(recursed)continue;
    if(it[v]>=off[v+1]){
     call[--csp]=0;
     if(csp>0){low[call[csp-1]]=Math.min(low[call[csp-1]],low[v]);}
     if(low[v]==index[v]){
      while(true){int w=stack[--sp];onstack[w]=false;comp[w]=ncomp;if(w==v)break;}
      ncomp++;
     }
    }
   }
  }
  System.out.println("components="+ncomp);
  // target roots
  for(int t=2;t<a.length;t++){
   int dense=Integer.parseInt(a[t]);
   int c=comp[dense];
   ArrayList<Integer> members=new ArrayList<>();
   for(int v=0;v<n;v++)if(comp[v]==c)members.add(v);
   System.out.println("\n=== dense "+dense+" (mat "+ids[dense]+") comp "+c+" members="+members.size()+" ===");
   TreeMap<String,Integer> byclass=new TreeMap<>();
   for(int v:members)byclass.merge(names[cls[v]],1,Integer::sum);
   for(var e:byclass.entrySet())System.out.println("  "+e.getValue()+"\t"+e.getKey());
   // outbound edges to String
   int strIdx=-1;for(int i=0;i<names.length;i++)if(names[i].equals("java.lang.String"))strIdx=i;
   TreeMap<Integer,ArrayList<Integer>> strEdges=new TreeMap<>();
   for(int v:members)for(int j=off[v];j<off[v+1];j++){int w=refs[j];if(cls[w]==strIdx&&comp[w]!=c)strEdges.computeIfAbsent(v,k->new ArrayList<>()).add(w);}
   System.out.println("  --- member->String edges (target outside comp): "+strEdges.values().stream().mapToInt(List::size).sum()+" from "+strEdges.size()+" members");
   for(var e:strEdges.entrySet()){
    System.out.println("    from dense "+e.getKey()+" mat "+ids[e.getKey()]+" class "+names[cls[e.getKey()]]);
    for(int w:e.getValue())System.out.println("      -> dense "+w+" mat "+ids[w]);
   }
  }
  // optional: dump string contents for given MAT ids
  if(System.getProperty("dumpStrings")!=null){
   Method open=MatExtract.class.getDeclaredMethod("open",String.class,boolean.class);open.setAccessible(true);ISnapshot s=(ISnapshot)open.invoke(MatExtract.INSTANCE,System.getProperty("dumpStrings"),true);
   try{
    for(String mid:System.getProperty("stringIds").split(",")){
     IObject o=s.getObject(Integer.parseInt(mid.trim()));
     System.out.println("STRING "+mid+" = ["+string(o)+"]");
    }
   }finally{s.dispose();}
  }
 }
}
