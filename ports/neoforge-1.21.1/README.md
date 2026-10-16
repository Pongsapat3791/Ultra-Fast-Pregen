# Ultra Fast Pregen สำหรับ NeoForge 1.21.1

พอร์ตของ Ultra Fast Pregen 1.20.1-12 (Fabric) ไปเป็น NeoForge 1.21.1 เครื่องมือสร้างโลกแบบ batch ใน RAM ตัวเดียวกัน
มีคำสั่ง ไฟล์ตั้งค่า ไฟล์ save และการแก้ทุกอย่างของรุ่น 1.20.1-12 อ่านรายละเอียดได้ใน [README หลัก](../../README.md)
(เร็วขึ้นบนเครื่อง core เยอะ, ชังก์เดิมต่อกับชังก์ใหม่ได้เนียน, หยุดแล้วรันต่อได้ผลเหมือนรันรวดเดียว, ลด batch เองเมื่อ RAM ไม่พอ ฯลฯ)

- **ต้องการ:** NeoForge 21.1.1 ขึ้นไป (ทดสอบกับ 21.1.259) และ Java 21
- **ไฟล์:** `build/libs/ultrafastpregen-neoforge-1.21.1-12.jar` ใส่ในโฟลเดอร์ `mods/` ของ server โดย `12` คือรอบอัปเดตเดียวกับ Fabric
- **ติดตั้ง:** ลงที่ server อย่างเดียวก็พอ ผู้เล่นไม่ต้องลง เพราะ mod ไม่มี packet ของตัวเอง
- **คำสั่ง:** `/pregen start|resume|reset|stop|status|world|center` ต้องเป็น op ระดับ 2
- **ไฟล์ตั้งค่า:** `config/ultrafastpregen.conf` ใช้ key เดียวกัน (`skip_existing_chunks=true` เป็นค่าเริ่มต้น)
  ไฟล์ `inmemory-pregen.conf` และไฟล์ save `fastpregen.dat` ของรุ่น 9 ถูกย้ายมาใช้ต่อเอง

## ความเร็ว

วัดบนเครื่องพัฒนา (12 thread) พื้นที่ 2000×2000 blocks, batch 1000, heap 8 GB ด้วย `-PbatchBenchmark=1000 -PbatchBenchmark.worldK=2`:

| | เวลา | ความเร็ว |
|---|---|---|
| Fabric 1.20.1-12 | 51.0 วินาที | 311.3 ชังก์/วินาที |
| NeoForge 1.21.1-12 | 51.0 วินาที | 311.2 ชังก์/วินาที |
| Fabric 1.20.1-9 | 54.9 วินาที | 288.9 ชังก์/วินาที |
| NeoForge 1.21.1-9 | 55.8 วินาที | 284.4 ชังก์/วินาที |

รุ่น 12 เร็วกว่ารุ่น 9 ราว 8–9% ทั้งสอง loader ยังไม่ได้วัดบนเครื่อง i9 ที่ใช้ทำตารางใน README หลัก

## ต่างจากรุ่น 1.20.1 ตรงไหน

Minecraft 1.21 เปลี่ยนวิธีสร้างชังก์ (`ChunkStatus` → `ChunkStep`/`ChunkPyramid`) ตัวพอร์ตจึงปรับตามนี้:

- **ขั้นตอนของ Minecraft:** ทุกขั้นที่ไม่ได้ใช้ทางลัด (structure, carvers, features, แสง, mob spawn และ `terrain_pipeline=vanilla`)
  เรียก `ChunkStep` ของ Minecraft 1.21 เอง ผ่าน chunk holder ที่ชี้ไปยังชังก์ใน RAM
  holder จะบอกว่าชังก์ถึงขั้นไหนตามสถานะจริง แบบเดียวกับ holder ของ server
- **biome ก่อน surface:**
  - 1.21 จะสร้าง noise และ surface ของชังก์หนึ่ง หลังจากชังก์ข้างเคียงทั้ง 8 มี biome แล้วเท่านั้น
  - Ultra Fast Pregen ทำตามนี้ โดยคำนวณ biome ให้วงรอบนอกพื้นที่ terrain เพิ่มอีก 1 ชังก์
  - ผลคือ surface อ่าน biome ของชังก์ข้างเคียงที่เก็บไว้เสมอ เหมือน vanilla 1.21
- **step ที่ server ค้างคิวไว้:**
  - ใน 1.21 step จะทำงานซ้ำแม้ชังก์ถึงขั้นนั้นแล้ว (1.20 ข้ามให้)
  - ชังก์ที่ server กำลังสร้างค้างอยู่และ Ultra Fast Pregen แทนที่ไปแล้ว จึงอาจถูกสร้าง terrain หรือวาง features ซ้ำ
  - `ChunkStepMixin` กันกรณีนี้ไว้ step ของแสงและขั้น FULL ยังทำงานตามปกติ
- **ลาวาในถ้ำลึก:**
  - ตอนวางลาวาใต้ระดับลาวา (y ≤ -56) carver อ่านค่า "ต้องอัปเดต fluid" ที่ aquifer ค้างไว้จากการเรียกครั้งก่อน
  - ทางลัดของ aquifer ระหว่างสร้าง noise ตั้งค่านี้ไม่ตรงกับ Minecraft ในบางตำแหน่ง
  - ผลคือลาวาบางก้อนถูกตั้งให้ไหลตอนโหลดชังก์ครั้งแรก ทั้งที่ vanilla ไม่ได้ตั้ง (บล็อกเหมือนกันทุกบล็อก)
  - รุ่นนี้แก้แล้ว: เมื่อสร้าง noise เสร็จ aquifer จะตั้งค่านี้แบบเดียวกับ Minecraft ทุกครั้ง
  - พบตอนเทียบทางลัดกับขั้นตอนของ Minecraft 1.21 และแก้ใน Fabric ตั้งแต่ 1.20.1-10
- **รัศมีของแต่ละ step:** step ของ 1.21 ได้ชังก์รอบข้างระยะ 1 ตามที่ server ให้ ชังก์ที่ไกลกว่านั้นอ่านจาก grid ผ่าน `WorldGenRegionMixin`
  (รุ่น 1.20.1 ให้ระยะตาม `ChunkStatus#getRange` เพื่อ Lithium แต่ 1.21 ไม่มีค่านี้แล้ว)
- **ลงทะเบียนคำสั่ง:** ผ่าน `RegisterCommandsEvent` ของ NeoForge แทน mixin (`neoforge.NeoForgeEntrypoint`)
- **ที่อยู่ config:** ใช้ `FMLPaths.CONFIGDIR`

## NeoForge

- **ใช้ได้ตามปกติ:** biome modifier, structure modifier และ datapack เพราะทุกขั้นอ่าน registry และ biome ของโลกตรง ๆ
- **การตรวจ mixin อัตโนมัติ:** ทำงานเหมือนรุ่น Fabric โดยตรวจ `ChunkStatusTasks` แทน `ChunkStatus`
  - NeoForge แก้คลาส worldgen ด้วย patch ไม่ใช่ mixin
  - patch ที่โดนส่วนนี้คือ `Beardifier` ที่เพิ่ม `PieceBeardifierModifier` ซึ่งยังทำงานกับทางลัดได้ เพราะเพิ่มแค่ชิ้นส่วนเข้ารายการเดิม
- **event ของชังก์:** Ultra Fast Pregen เขียนชังก์ลงไฟล์ region เอง จึงไม่ผ่าน event บางตัว
  - **`ChunkDataEvent.Save`:** ไม่ถูกเรียก เหมือนชังก์ที่ยังไม่เคยโหลด
  - **`ChunkEvent.Load` ตอน server โหลดชังก์ครั้งแรก:** ถูกเรียก แต่ `isNewChunk()` เป็น `false`
  - mod ที่ทำงานเฉพาะกับชังก์ใหม่ผ่าน event เหล่านี้ (เช่น retrogen) จะไม่เห็นชังก์ที่ Ultra Fast Pregen สร้าง
- **data attachment:** ถ้า mod ใส่ attachment ให้ชังก์ระหว่าง worldgen ข้อมูลจะถูกบันทึกไปด้วย เพราะใช้ `ChunkSerializer` ของ NeoForge

## ความถูกต้อง (1.21.1-12 ทดสอบบน NeoForge 21.1.259)

- **Terrain checksum:**
  - `runTerrainBenchmark` เทียบ vanilla กับทางลัดใน JVM เดียวกัน
  - checksum ของบล็อก, biome, heightmap และ fluid ตรงกันทั้ง 3 seed
  - preset อื่นของ vanilla (large biomes, amplified, nether, end, caves, floating islands) ตรงกับ Minecraft 1.21.1 ทุกบล็อก
  - ทางลัดเร็วกว่า vanilla ราว 1.7 เท่า (วัดในรุ่น 9: 397.9 เทียบ 234.2 ชังก์/วินาที, 8 thread, 3 seed × 256 ชังก์)
- **เทสต์บน server จริง `runPartialBatchCheck`:** ผ่านครบทุกข้อ
  - ผลเหมือนเดิมรอบหมู่บ้าน ทั้งแบบข้ามการคำนวณทีละบล็อกและแบบคำนวณทุกบล็อก
  - ลำดับ features ได้ผลเหมือนแบบแบ่ง phase ทุกบล็อก
  - ทางลัดได้ผลเหมือนขั้นตอนของ Minecraft 1.21 (`terrain_pipeline=vanilla`) หลัง carvers และ features ทั้งบล็อก, heightmap และตำแหน่งที่รอ post-processing
  - biome ที่เก็บในชังก์ตรงกับที่คำนวณสดทุก quart
  - `skip_existing_chunks`, ไฟล์ save และการรันต่อ
  - ขอบ spawn และการแทนที่ชังก์ที่ server ถืออยู่
  - รอยต่อ batch: 597 บล็อกต่อคอลัมน์ตรงขอบ เทียบกับ 6,299 เมื่อปิดการแก้รอยต่อ และแบ่ง batch ได้ผลเหมือนรันลำดับเดียวกันในครั้งเดียว (ต่างกัน 2 บล็อก)
  - หยุดแล้วรันต่อ: ต่างจากรันรวดเดียว 2 บล็อก (ไม่นับรังผึ้ง)
  - ต่อกับพื้นที่เดิม: ขอบต่างกัน 0 บล็อก (ถ้าปิดการแก้ต่างกัน 17,470)
  - nether และ end สร้างครบ
  - batch ทำซ้อนกัน (แสงและการบันทึกพร้อมกับ batch ถัดไป) ทำงานในทุกการรันของเทสต์

## บิลด์และทดสอบ

รันจากโฟลเดอร์หลักของ repo (ใช้ Gradle wrapper ตัวเดียวกัน):

```powershell
.\gradlew.bat -p ports/neoforge-1.21.1 build
```

- **`runTerrainBenchmark`:** เทสต์ย่อยทั้งหมด และ benchmark Stage 2 แบบ vanilla/ทางลัด
  ```powershell
  .\gradlew.bat -p ports/neoforge-1.21.1 runTerrainBenchmark '-Pbenchmark.compare=true' '-Pbenchmark.threads=8'
  ```
- **`runPartialBatchCheck`:** เทสต์บน server จริงที่ `ports/neoforge-1.21.1/build/partial-batch-check`
  - ต้องมี `eula.txt` และ `server.properties` ในโฟลเดอร์นั้น
  - ตั้ง `level-name` ใหม่ทุกครั้ง
  - ใช้ `-PpipelineBenchmark=true`, `-PbatchBenchmark=...` และ `-PcheckHeap=8G` ได้เหมือนรุ่น Fabric
- **`runServer`:** เปิด server สำหรับพัฒนาที่ `ports/neoforge-1.21.1/run`

โค้ดเทสต์ (`src/test`) อยู่ใน mod เดียวกับโค้ดหลักตอนรันพัฒนา เพราะ NeoForge แยก mod เป็น Java module
และเทสต์ใช้ member ระดับ package ของโค้ดหลัก ไฟล์ jar ที่บิลด์ได้มีเฉพาะโค้ดหลัก
