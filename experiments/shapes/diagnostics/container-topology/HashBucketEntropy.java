import java.lang.reflect.*;import java.util.*;import shapes.*;
/** A real JDK HashMap control: equal sizes, key/value types and leaf shapes.
 * Only String contents (excluded from shape keys) and resulting bucket chains differ.
 */
public class HashBucketEntropy {
 static Field table,next,key,value;
 static ArrayList<String> names=new ArrayList<>();static ArrayList<int[]> refs=new ArrayList<>();
 static int add(String c,int... r){int id=names.size();names.add(c);refs.add(r);return id;}
 static int node(Object n)throws Exception{
  int k=add("java.lang.String",0);int[] r=next.get(n)==null?new int[]{k,1}:new int[]{k,1,node(next.get(n))};
  return add("java.util.HashMap$Node",r);
 }
 static void run(int count,int size,boolean flatten)throws Exception{
  names.clear();refs.clear();add("byte[]");add("Value");int[] roots=new int[count];Random random=new Random(42);
  Set<String> chainHist=new HashSet<>();
  for(int m=0;m<count;m++){
   HashMap<String,Object> map=new HashMap<>();for(int i=0;i<size;i++)map.put(Long.toHexString(random.nextLong()),Boolean.TRUE);
   Object[] tab=(Object[])table.get(map);ArrayList<Integer> heads=new ArrayList<>();ArrayList<Integer> hist=new ArrayList<>();
   for(Object n:tab)if(n!=null){
    if(flatten){for(Object p=n;p!=null;p=next.get(p)){int k=add("java.lang.String",0);heads.add(add("java.util.HashMap$Node",k,1));}}
    else heads.add(node(n));
    int len=0;for(Object p=n;p!=null;p=next.get(p))len++;hist.add(len);
   }
   Collections.sort(hist);chainHist.add(hist.toString());int t=add("java.util.HashMap$Node[]",heads.stream().mapToInt(Integer::intValue).toArray());roots[m]=add("java.util.HashMap",t);
  }
  String[] nn=names.toArray(String[]::new);long[] shallow=new long[nn.length];Arrays.fill(shallow,16);
  ShapeTable t=new ShapesAnalyzer(ObjectGraph.Companion.of(nn,shallow,refs.toArray(int[][]::new))).analyze(roots);
  System.out.println("maps="+count+" entries_per_map="+size+" normalized="+flatten+" objects="+nn.length+" total_shapes="+t.getEntries().size()+" distinct_map_shapes="+t.getRootsHistogram().size()+" distinct_chain_histograms="+chainHist.size());
 }
 public static void main(String[] args)throws Exception{
  table=HashMap.class.getDeclaredField("table");table.setAccessible(true);Class<?> n=Class.forName("java.util.HashMap$Node");next=n.getDeclaredField("next");next.setAccessible(true);
  for(int size:new int[]{2,10,30,100,200}){run(3000,size,false);run(3000,size,true);}
 }
}
