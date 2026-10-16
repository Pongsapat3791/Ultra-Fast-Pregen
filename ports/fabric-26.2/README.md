# Ultra Fast Pregen สำหรับ Fabric 26.2

พอร์ตของ Ultra Fast Pregen 13 ไปเป็น Minecraft 26.2 (Fabric) มีคำสั่ง (รวม `/pregen set` และ bossbar) ไฟล์ตั้งค่า ไฟล์ save
และการแก้ทุกอย่างของรุ่น 13 อ่านรายละเอียดได้ใน [README หลัก](../../README.md)

- **ต้องการ:** Fabric Loader 0.16 ขึ้นไป และ Java 25 (Fabric API ไม่จำเป็น)
- **ไฟล์:** `build/libs/ultrafastpregen-fabric-26.2-13.jar` โดย `13` คือรอบอัปเดตเดียวกับรุ่นอื่น
- **ติดตั้ง:** ลงที่ server อย่างเดียวก็พอ ผู้เล่นไม่ต้องลง

## ต่างจากรุ่น 1.20.1 ตรงไหน

โค้ดเริ่มจากรุ่น NeoForge 1.21.1 (ระบบสร้างชังก์แบบ `ChunkStep` ของ Minecraft 1.21 ขึ้นไป) แล้วปรับตาม API ของ 26.x
(Minecraft 26.x ไม่ obfuscate จึงใช้ Loom แบบไม่ remap)

- **ทางลัดของ aquifer ปิดอยู่:** Minecraft 26.x เขียน aquifer ใหม่ (หาจุดอ้างอิง 4 จุดแทน 3 และข้ามการสุ่มเหนือระดับที่กำหนดเอง)
  ทางลัดของรุ่น 1.20 จะให้ผลไม่ตรงกับ algorithm ใหม่ จึงใช้ aquifer ของ Minecraft
  การข้ามการคำนวณทีละบล็อกยังทำงานกับ cell ที่เป็นหินทั้งก้อน ส่วน cell อากาศใต้ผิวดินคำนวณทีละบล็อก
- **biome ตอนทำ surface:** 26.x คำนวณ biome แบบ lazy ในตัวอยู่แล้ว ทางลัดเดิมจึงไม่จำเป็น
- **ขั้นตอนของ Minecraft:** step ของ 26.x ได้ชังก์รอบข้างระยะ 1 ตามที่ server ให้ ชังก์ที่ไกลกว่านั้นอ่านจาก grid ผ่าน `WorldGenRegionMixin` (เหมือนรุ่น 1.21.1)
- **Fabric API:** mixin ของ Fabric API ใน `ChunkStatusTasks` แตะเฉพาะขั้น FULL (event ของชังก์) จึงไม่ปิดทางลัดของ terrain

## ความถูกต้อง (ทดสอบบน Minecraft 26.2, Fabric Loader 0.19.5)

- **เทสต์ชุดเร็ว (`runTerrainBenchmark`):** ผ่านทุกข้อ ทางลัดได้ terrain ตรงกับ Minecraft 26.2 ทุกบล็อก ทั้ง overworld และ preset อื่น
  (large biomes, amplified, nether, end, caves, floating islands)
- **เทสต์ชุดเต็มบน server จริง (`runPartialBatchCheck`):** ผ่านทุกข้อ
  - ทางลัดได้ผลเหมือนขั้นตอนของ Minecraft 26.2 ทั้งรอบหมู่บ้านและหลัง features
  - แบ่ง batch ได้บล็อกเหมือนรันลำดับเดียวกันครั้งเดียวทุกบล็อก หยุดแล้วรันต่อต่างจากรันรวดเดียว 0 บล็อก
  - ต่อกับพื้นที่เดิม: ขอบต่าง 0 บล็อก (ถ้าปิดการแก้ต่างกัน 17,618)
  - แสงทุกบล็อกตรงกับ light engine ของ Minecraft และชังก์ที่ server ถือค้างแล้วถูกเขียนทับไม่มืด
    (26.x ไม่มี spawn chunks แล้ว เทสต์นี้ใช้ forced ticket ถือชังก์แทน)
  - nether และ end สร้างครบ
- **jar ที่แจกจริงบน server Fabric 26.2 (loader 0.19.5, Java 25, ไม่มี Fabric API):** โหลดได้, `/pregen set` ทำงาน, ใช้ทางลัดของ terrain ครบ
  พื้นที่ 1k (4,096 ชังก์) 22 วินาที (185.4 ชังก์/วินาที บนเครื่องพัฒนา 12 thread) ไม่มีชังก์มืด ใต้ดินไม่มี sky light และมี block light จาก geode
- ยังไม่ได้วัดบนเครื่อง i9 ที่ใช้ทำตารางใน README หลัก

## บิลด์และทดสอบ (จากโฟลเดอร์โปรเจกต์หลัก)

```powershell
.\gradlew.bat -p ports/fabric-26.2 build
.\gradlew.bat -p ports/fabric-26.2 runTerrainBenchmark
.\gradlew.bat -p ports/fabric-26.2 runPartialBatchCheck
```

Gradle ดาวน์โหลด JDK 25 ให้เองครั้งแรก `runPartialBatchCheck` ใช้โลกทดสอบที่ `build/partial-batch-check`
(ต้องมี `eula.txt` และ `server.properties`, ตั้ง `level-name` ใหม่ทุกครั้ง)
