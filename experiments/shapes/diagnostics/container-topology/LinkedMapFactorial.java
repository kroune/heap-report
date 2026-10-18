import java.lang.reflect.*;import java.util.*;import shapes.*;
public class LinkedMapFactorial {
 static class A{} static class B{} static class C{} static class D{}
 static ArrayList<String> names=new ArrayList<>();static ArrayList<int[]> refs=new ArrayList<>();
 static Field table,head,tail,key,next,before,after;
 static int add(String c){int i=names.size();names.add(c);refs.add(new int[0]);return i;}
 static int[] ids(Object... values){return Arrays.stream(values).filter(Objects::nonNull).mapToInt(v->(Integer)v).distinct().toArray();}
 static Field field(Class<?> c,String n)throws Exception{Field f=c.getDeclaredField(n);f.setAccessible(true);return f;}
 static void run(List<LinkedHashMap<Object,Object>> maps,String dataset,String mode)throws Exception{
  names.clear();refs.clear();int val=add("Present");int[] roots=new int[maps.size()];int m=0;
  for(LinkedHashMap<Object,Object> map:maps){
   IdentityHashMap<Object,Integer> obj=new IdentityHashMap<>();int mid=add("LinkedHashMap"),tid=add("Table[]");roots[m++]=mid;
   for(Object e:map.entrySet()){obj.put(e,add("Entry"));Object k=key.get(e);obj.put(k,add(k.getClass().getSimpleName()));}
   boolean order=mode.equals("raw")||mode.equals("order_only");boolean buckets=mode.equals("raw")||mode.equals("buckets_only");
   for(Object e:map.entrySet()){
    ArrayList<Integer> es=new ArrayList<>();es.add(obj.get(key.get(e)));es.add(val);
    if(order){if(before.get(e)!=null)es.add(obj.get(before.get(e)));if(after.get(e)!=null)es.add(obj.get(after.get(e)));}
    if(buckets&&next.get(e)!=null)es.add(obj.get(next.get(e)));
    refs.set(obj.get(e),es.stream().distinct().mapToInt(Integer::intValue).toArray());
   }
   if(buckets){Object[] array=(Object[])table.get(map);refs.set(tid,Arrays.stream(array).filter(Objects::nonNull).mapToInt(obj::get).toArray());}
   else refs.set(tid,map.entrySet().stream().mapToInt(obj::get).toArray());
   refs.set(mid,order?ids(tid,obj.get(head.get(map)),obj.get(tail.get(map))):new int[]{tid});
  }
  long[] sh=new long[names.size()];Arrays.fill(sh,16);ObjectGraph g=ObjectGraph.Companion.of(names.toArray(String[]::new),sh,refs.toArray(int[][]::new));
  ShapeTable t=new ShapesAnalyzer(g).analyze(roots);
  System.out.println("dataset="+dataset+" maps="+maps.size()+" entries=20 mode="+mode+" shapes="+t.getEntries().size()+" map_variants="+t.getRootsHistogram().size()+" objects="+t.getReachableCount());
 }
 public static void main(String[] args)throws Exception{
  Class<?> node=Class.forName("java.util.HashMap$Node"),entry=Class.forName("java.util.LinkedHashMap$Entry");
  table=field(HashMap.class,"table");head=field(LinkedHashMap.class,"head");tail=field(LinkedHashMap.class,"tail");key=field(node,"key");next=field(node,"next");before=field(entry,"before");after=field(entry,"after");
  for(String dataset:new String[]{"fixed_ABCD_order","shuffled_ABCD_order","all_A"}){
   ArrayList<LinkedHashMap<Object,Object>> maps=new ArrayList<>();Random rng=new Random(42);
   for(int m=0;m<3000;m++){
    ArrayList<Object> keys=new ArrayList<>();for(int i=0;i<5;i++){keys.add(new A());keys.add(dataset.equals("all_A")?new A():new B());keys.add(dataset.equals("all_A")?new A():new C());keys.add(dataset.equals("all_A")?new A():new D());}
    if(dataset.equals("shuffled_ABCD_order"))Collections.shuffle(keys,rng);
    LinkedHashMap<Object,Object> map=new LinkedHashMap<>();for(Object k:keys)map.put(k,Boolean.TRUE);maps.add(map);
   }
   for(String mode:new String[]{"raw","order_only","buckets_only","logical"})run(maps,dataset,mode);
  }
 }
}
