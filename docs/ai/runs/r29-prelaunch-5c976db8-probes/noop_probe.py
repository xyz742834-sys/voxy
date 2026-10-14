from pathlib import Path
import subprocess
r=Path.cwd();p=r/'.agent-run/prelaunch';o=p/'noop-src';o.mkdir(exist_ok=True)
def put(name,s):
 f=o/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(s);return str(f)
files=[]
files.append(put('me/cortex/voxy/common/voxelization/VoxelizedSection.java','''package me.cortex.voxy.common.voxelization; public class VoxelizedSection {public int x,y,z,lvl0NonAirCount; public long[] section=new long[5000];public void setPosition(int a,int b,int c){x=a;y=b;z=c;}public static int getBaseIndexForLevel(int l){return 4096;}}'''))
files.append(put('me/cortex/voxy/common/world/other/Mapper.java','''package me.cortex.voxy.common.world.other; public class Mapper {public static boolean isAir(long v){return v==0;}}'''))
files.append(put('me/cortex/voxy/commonImpl/VoxyCommon.java','''package me.cortex.voxy.commonImpl;public class VoxyCommon {public static boolean IS_MINE_IN_ABYSS=false;}'''))
files.append(put('me/cortex/voxy/common/world/WorldSection.java','''package me.cortex.voxy.common.world;public class WorldSection {public int lvl,released;public long[] data=new long[32768];public int updateEmptyChildState(WorldSection s){throw new AssertionError("unexpected parent");}public void release(){released++;}public void addNonEmptyBlockCount(int d){throw new AssertionError("unexpected count change");}public boolean updateLvl0State(){throw new AssertionError("unexpected state change");}}'''))
files.append(put('me/cortex/voxy/common/world/WorldEngine.java','''package me.cortex.voxy.common.world;public class WorldEngine {public static final int MAX_LOD_LAYER=4,UPDATE_TYPE_BLOCK_BIT=1,UPDATE_TYPE_CHILD_EXISTENCE_BIT=2;public boolean isLive=true;public int acquisitions,dirty;public WorldSection leaf=new WorldSection();public WorldSection acquire(int l,int x,int y,int z){if(l!=0)throw new AssertionError("unexpected parent");acquisitions++;return leaf;}public void markDirty(WorldSection s,int f,int n){dirty++;}}'''))
files.append(put('NoopProbe.java','''import me.cortex.voxy.common.world.*;import me.cortex.voxy.common.voxelization.*;public class NoopProbe {public static void main(String[]a){var e=new WorldEngine();var s=new VoxelizedSection();WorldUpdater.insertUpdate(e,s);if(e.dirty!=0||e.acquisitions!=1||e.leaf.released!=1)throw new AssertionError();System.out.println("{\\"actual_insertUpdate_executed\\":true,\\"unchanged_air_dirty_callbacks\\":0,\\"leaf_released\\":true,\\"mock_collaborators\\":true}");}}'''))
files.append(str(r/'src/main/java/me/cortex/voxy/common/world/WorldUpdater.java'))
j='/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin/';classes=p/'noop-classes';classes.mkdir(exist_ok=True)
for name,cmd in [('noop-compile',[j+'javac','-d',str(classes)]+files),('noop',[j+'java','-Xmx128m','-cp',str(classes),'NoopProbe'])]:
 x=subprocess.run(cmd,capture_output=True,text=True,timeout=10);(p/(name+'.log')).write_text(x.stdout+x.stderr);print(name,x.returncode,x.stdout,x.stderr);assert x.returncode==0
