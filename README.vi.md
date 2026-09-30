# Living Villages

*[English](README.md)*

Mod Fabric cho Minecraft 1.21.1, chạy phía server, giúp làng dân tự lớn thành thành trì có tường thành. Mỗi làng có một trưởng làng quyết định làng cần gì tiếp theo: nhà ở khi hết giường, nông trại khi thiếu nông dân, xưởng khi dân không có việc, chuồng cho gia súc. Một dân làng trở thành thợ xây, đi tới một chỗ trống gần chuông làng và xây dần từng khối. Làng lớn dần thì tự quây hàng rào gỗ, rồi tường đá với tháp và cổng vòm, rồi chiếu sáng đường tối và giữ cho đường sá sạch sẽ. Mẫu công trình lấy từ chính bộ công trình của làng nên luôn đúng phong cách; nếu có cài Better Village thì dùng mẫu của Better Village.

Mod không thêm khối, vật phẩm, sinh vật hay texture mới. Người chơi không cần cài mod ở máy mình.

## Tính năng mới so với bản 0.1

Bản 0.1 chỉ xây nhà khi làng hết giường. Giờ có thêm:

- **Chặt cây** mọc trên chỗ xây (không rơi đồ) và trồng lại cây non quanh công trình mới.
- **Nhu cầu, tâm trạng và trưởng làng:** nhà ở, thức ăn, việc làm, an ninh từ 0 đến 100, tâm trạng của làng, và một trưởng làng được chọn trong dân.
- **Xây theo nhu cầu:** trưởng làng xây nhà ở, nông trại, xưởng cho nghề đang thiếu hoặc chuồng gia súc, tùy làng thiếu gì nhất.
- **Cấp làng:** Xóm, Làng, Thị trấn, Thành phố; cấp càng cao càng được xây nhiều và xa hơn.
- **Mỗi nghề một việc:** cho gia súc sinh sản và xén lông, lò hun khói, vạc nước, câu cá, chữa thương, mục sư chữa dân làng zombie, thợ rèn làm làng an toàn hơn hoặc xây nhanh hơn, người vẽ bản đồ mở rộng bán kính xây.
- **Tên:** tên làng, họ theo hộ gia đình, tên dân và lính gác, tiêu đề chào khi bước vào làng.
- **Lời nói:** dân làng nói những câu ngắn về chuyện đang thật sự xảy ra trong làng.
- **Biên niên sử và bia mộ:** làng ghi biên niên sử (cả thành sách trên lectern của thủ thư) và dựng bia cho dân có tên khi mất.
- **Pháo hoa mừng lên cấp:** làng lên cấp thì bắn một tràng pháo hoa trên quảng trường.
- **Bảng vật liệu:** mang vật liệu làng cần tới đổi lấy ngọc với trưởng làng để làng xây nhanh hơn.
- **Đường làng:** công trình mới được nối đường vào đường làng, do thợ đá lát.
- **Hàng rào gỗ:** từ cấp Làng, thợ đá dựng một vòng hàng rào gỗ quanh làng (bao hết mọi nhà và bàn nghề). Cổng mở ở chỗ đường cắt qua. Hàng rào dời ra khi làng có thêm nhà mới nằm ngoài.
- **Tường đá, tháp và cổng vòm:** từ cấp Thị trấn, hàng rào gỗ được thay bằng tường đá: cùng hình đa giác, cao hơn, có tháp 5×5 ở các góc và dọc tường (cầu thang xoắn bên trong, cửa vào mặt trong), cổng có vòm. Ở cấp Thành phố, tường có thêm lỗ châu mai ở mọi con tiêu.
- **Nhà xây trong tường:** khi tường đá đã dựng xong, nhà ở và xưởng được chọn chỗ bên trong tường; nông trại và chuồng ra ngoài gần cổng.
- **Vòng tường ngoài:** ở cấp cao nhất, một vòng tường thứ hai mở rộng ra quanh vòng trong.
- **Đuốc:** chỗ tối trên đường, sân chuông, bên trong tường và đỉnh tường được cắm đuốc tự động.
- **Chăm sóc làng:** dân làng vá ổ gà đường, quét tuyết trên đường và cắt cỏ dại gần cửa nhà. Có thể bật thêm hàng rào vườn quanh nhà mod xây (`homeFences`).

Tính năng nào cũng có công tắc trong config; tắt hết thì mod chạy như bản 0.1. Thế giới từ bản 0.1 nạp được, không mất dữ liệu.

## Yêu cầu

- Minecraft **1.21.1**
- Fabric Loader **0.16.0** trở lên
- Fabric API (bản build dùng 0.116.17+1.21.1)
- Java 21

## Cài đặt

1. Cài Fabric Loader cho 1.21.1.
2. Chép `livingvillages-<phiên bản>.jar` và Fabric API vào thư mục `mods`. Với server riêng thì chỉ server cần cài.
3. Mở game một lần. File `config/livingvillages.json` sẽ tự được tạo với giá trị mặc định.

Mod tự chạy ngay sau khi cài, không cần gõ lệnh để bật.

## Cách hoạt động

- **Làng** được nhận diện qua chuông (điểm tụ họp). Mỗi chuông là một làng. Phong cách làng (đồng bằng, sa mạc, xavan, tuyết, taiga) lấy theo dân làng ở lần đầu mod thấy làng và không đổi về sau. Dân làng rừng rậm và đầm lầy được tính là đồng bằng.
- **Mẫu công trình** được đọc lúc chạy từ bộ `minecraft:village/<phong cách>/houses` và phân loại theo thứ bên trong: **xưởng** có khối nghề (xưởng cho nghề đó), **nông trại** có đất cày và thùng ủ phân (composter), **chuồng** là khu rào không có giường, **nhà ở** có giường. Các mảnh khác (trang trí, điểm tụ họp) không bao giờ được xây. Datapack và mod sửa bộ này sẽ tự được áp dụng.
- **Xây gì** do trưởng làng quyết định, tối đa một lần mỗi ngày Minecraft (`cooldownTicks`) và chỉ khi làng có ít nhất 2 dân trưởng thành. Nhu cầu chưa đủ đầu tiên được ưu tiên:
  1. nhà ở dưới `needThreshold` → nhà ở;
  2. thức ăn dưới `needThreshold` → nông trại (trừ khi vẫn còn thùng ủ phân chưa ai nhận: lúc đó làng thiếu người chứ không thiếu ruộng);
  3. việc làm dưới `needThreshold` → xưởng cho nghề mà làng có ít người nhất;
  4. có người chăn nuôi (chăn cừu, bán thịt, thuộc da, làm tên) nhưng quanh làng không có gia súc của họ → chuồng, xây xong có sẵn một cặp gia súc đó;
  5. còn lại thì không xây (`status` ghi lý do: làng đang ổn, hoặc làng cần lớn hơn để xây tiếp).
- **Cấp làng:** Xóm, Làng, Thị trấn, Thành phố. Mỗi cấp cần đủ số dân trưởng thành và số nghề khác nhau (Thị trấn cần thêm thủ thư, Thành phố cần thủ thư và mục sư), và quyết định số công trình mod được xây (4 / 10 / 20 / 32) cùng khoảng cách xa nhất tới chuông (48 / 64 / 80 / 96 khối). Cấp được tính ngay khi mod thấy làng, nên làng vanilla có sẵn thường bắt đầu trên cấp Xóm. Cấp chỉ tăng; người chơi ở gần thấy tiêu đề khi làng lên cấp, và pháo hoa bắn lên trên quảng trường (phóng từ chỗ đất trống cách xa mọi người, nổ trên cao nên không ai bị thương, không cháy gì).
- **Mỗi nghề một việc** (trong giờ làm việc): người chăn cừu cho cừu sinh sản và xén lông; người bán thịt cho lợn sinh sản và bỏ thịt sống cùng than củi vào lò hun khói của mình; thợ thuộc da cho bò sinh sản và đổ nước vào vạc của mình; thợ làm tên cho gà sinh sản; ngư dân câu cá ở chỗ nước gần đó rồi bỏ cá tuyết hoặc cá hồi vào thùng của mình; mục sư chữa dân bị thương và chữa dân làng zombie trong làng (mỗi người tối đa một lần mỗi ngày; không đụng con có tên, bị dắt dây hay được người chơi giữ lại); người vẽ bản đồ đi một vòng quanh rìa làng. Thợ rèn giáp và thợ rèn vũ khí làm làng an toàn hơn, thợ rèn công cụ giúp xây nhanh hơn (mỗi người +10%, tối đa +30%), có người vẽ bản đồ thì bán kính xây rộng thêm 16. Gia súc chỉ được cho sinh sản tới giới hạn mỗi loại (tăng theo cấp làng) và không bao giờ bị giết; con có tên hoặc bị dắt dây không bị đụng tới.
- **Tên:** mỗi làng có một cái tên hợp với phong cách ("Làng Suối Bạc"). Mỗi nhà là một hộ có họ riêng, và dân làng mang họ của hộ có giường họ ngủ ("Trần Minh"). Tên đặt bằng name tag hoặc do mod khác đặt không bao giờ bị thay. Lính gác của Guard Villagers được đặt tên "Lính gác <tên>". Bước vào làng sẽ thấy tiêu đề ghi tên làng, cấp, số dân và tâm trạng.
- **Lời nói:** thỉnh thoảng một dân làng gần người chơi nói một câu ngắn trên đầu, chọn theo điều đang thật sự xảy ra: nhu cầu chưa đủ, công trình đang xây, em bé mới sinh, người vừa mất, việc của nghề mình, dự định của trưởng làng, tâm trạng, hay lời chào. Chữ là một text display vanilla, tự biến mất sau vài giây; chữ còn sót lại (ví dụ sau khi game bị tắt đột ngột) bị xóa ngay khi được nạp.
- **Biên niên sử:** làng ghi lại những gì xảy ra: sinh ("Nhà họ Trần vừa có thêm một em bé"), mất và nguyên nhân, dân bị biến thành zombie hay được chữa khỏi, dân gia nhập đội lính gác, công trình mới, lên cấp, trưởng làng mới, thắng hay thua cuộc đột kích và vật liệu được giao. Giữ `chronicleMaxEntries` mục mới nhất. Mỗi thủ thư giữ một cuốn sách biên niên sử trên lectern của mình khi lectern đó trống; lectern đang có sách khác không bao giờ bị đụng tới, sách bị lấy đi thì có cuốn mới ở mục tiếp theo. Sự kiện lớn (lên cấp, đột kích) báo trong chat, sự kiện nhỏ báo trên thanh hành động. Dân có tên và lính gác có tên khi mất có bia mộ (một khối tường đá và một tấm bảng ghi tên, nghề, ngày mất) trong nghĩa trang 7×7 ở rìa làng; em bé, dân bị zombie hóa và dân thành lính gác thì không.
- **Bảng vật liệu:** làng xin ba loại vật liệu dùng nhiều nhất của công trình đang xây hoặc sắp xây, ghi trên một tấm bảng cạnh chuông và thành giao dịch với trưởng làng (vật liệu → ngọc, mỗi giao dịch dùng một lần). Mỗi lần giao làm làng vui hơn và được ghi vào biên niên sử; giao đủ hết thì công trình đó xây nhanh gấp đôi và công trình sau bắt đầu không cần chờ. Làng không bao giờ cần bảng này: nó chỉ giúp làng lớn nhanh hơn.
- **Đường làng:** công trình xây xong được nối từ cửa tới đường làng gần nhất trong `roadSearchRadius` (đường đất, sa thạch mịn và đường của Regrowth), hoặc về chuông. Thợ đá đi dọc và lát đường (không có thợ đá thì chậm một nửa). Chỉ đổi cỏ, đất, đất thô và podzol thành đường đất; ở sa mạc cát thành sa thạch mịn; tránh nước, công trình và đất của nhà khác. Không tìm được đường thì bỏ qua.
- **Hàng rào và tường thành:** từ cấp Làng, thợ đá dựng vòng hàng rào gỗ quanh rìa ngoài của mọi nhà và bàn nghề. Cổng mở ở chỗ đường cắt qua. Lên cấp Thị trấn thì hàng rào được thay bằng tường đá gạch (sa thạch mịn ở sa mạc): tháp 5×5 ở các góc và dọc tường, cầu thang xoắn bên trong tháp, cửa vào từ mặt trong, cổng có vòm, có con tiêu. Lên Thành phố thì tường có thêm lỗ châu mai. Ở cấp cao nhất, một vòng tường thứ hai mở rộng ra. Mod không bao giờ đụng khối do người chơi đặt; ô bị người chơi phá vỡ được giữ mở, ô bị quái phá hoặc nổ thì được sửa lại. Tắt hẳn bằng `wallsEnabled = false`.
- **Đuốc:** mỗi `torchIntervalTicks` tick, một ô tối trên đường, sân chuông, bên trong tường hoặc trên khối tường được cắm đuốc. Độ sáng đã trên `torchLightLevel` thì không cắm thêm.
- **Chăm sóc làng:** mỗi `roadCareIntervalTicks` tick, một dân làng đang thức sửa vài thứ quanh họ: vá ổ gà (đất tự nhiên kẹp giữa hai đoạn đường), nâng đoạn đường bị lún, quét tuyết trên đường, cắt cỏ dại trên đường hoặc gần cửa nhà. `homeFences = true` thêm hàng rào vườn quanh từng nhà mod xây.
- **Chỗ xây** là đất tự nhiên bằng phẳng, khô ráo, cách chuông ít nhất 12 khối và trong bán kính xây của cấp làng (64 khối khi tắt cấp làng). Khi tường đã dựng xong, nhà ở và xưởng được đặt bên trong tường; nông trại và chuồng ra ngoài gần cổng. Mod không xây đè lên đường, công trình, giường, chuông, khối nghề hay đất của nhà khác. Chỗ hơi dốc sẽ có móng đỡ (đá cuội, riêng làng sa mạc là sa thạch).
- **Cây:** tối đa 4 cây tự nhiên mọc trong chỗ đặt nhà được chặt trước, không rơi đồ, và chỗ không có cây luôn được ưu tiên. Cây chỉ tính là tự nhiên khi có lá tự nhiên (không phải lá do người chơi đặt), nên nhà gỗ và cây trang trí của người chơi không bao giờ bị đụng tới. Cây khổng lồ và cây có tổ ong được giữ nguyên. Xây xong, mỗi cây đã chặt được trồng lại một cây non cùng loại, cách nhà 3–8 khối; nếu làng có nông dân thì một nông dân đi tới trồng.
- **Thứ tự xây:** móng, rồi dọn cỏ và san đất, rồi dựng nhà từng tầng từ dưới lên, cuối cùng mới đặt cửa, giường, đuốc, thảm và đồ trang trí. Khối chỉ được đặt vào ô trống hoặc ô có thứ thay thế được như cỏ. Khối người chơi đặt chắn đường được giữ nguyên. Rương không có đồ bên trong.
- **Thợ xây:** ưu tiên dân thất nghiệp, rồi thợ đá, rồi bất kỳ ai, trừ dân ngốc (nitwit) và trẻ con. Thợ xây phải đứng trong phạm vi `builderReach` khối mới đặt được, có vung tay và cầm khối đang đặt. Nếu thợ xây chết hoặc 60 giây không tới được công trường thì người khác thay. Không còn ai thì nhà tự xây với nửa tốc độ.
- **Không cần vật liệu:** mod tự tạo khối, thợ xây không phải đi gom vật liệu và không lấy đồ của làng hay của người chơi.
- **Tạm dừng** vào ban đêm, khi làng bị raid, và khi không có người chơi nào trong phạm vi `activeRange` khối. Làng ở xa thì dừng hẳn, không xây bù. Tiến độ được lưu lại, nên thoát game giữa chừng thì lần sau xây tiếp đúng chỗ.
- **Nhu cầu, tâm trạng, trưởng làng:** mỗi làng có bốn nhu cầu từ 0 đến 100 (nhà ở: giường trống; thức ăn: tỉ lệ nông dân; việc làm: dân thất nghiệp so với bàn nghề còn trống; an ninh: golem sắt và lính gác của Guard Villagers, trừ đi các lần bị quái tấn công gần đây) và tâm trạng tính từ chúng cộng các sự kiện nhạt dần (công trình mới làm làng vui, có người mất làm làng buồn). Dân trưởng thành có cấp nghề cao nhất làm trưởng làng (ưu tiên người có nghề: vanilla không cho giao dịch với dân thất nghiệp, mà bảng vật liệu giao dịch qua trưởng làng; trưởng làng thất nghiệp sẽ nhường ngay khi làng có người có nghề), mang danh hiệu "Trưởng làng <tên>" (hiện khi nhìn vào). Tên đặt bằng name tag được giữ nguyên. Trưởng làng không làm thợ xây, và được thay khi chết, bị biến đổi hoặc vắng mặt quá lâu.
- Nếu chuông bị phá, làng chuyển sang ngừng hoạt động và dự án tạm dừng. Đặt chuông lại gần đó thì làng hoạt động trở lại.

## Ngôn ngữ

Người chơi không cần cài mod, nên mọi chữ được tạo trên server theo ngôn ngữ đặt ở mục `language` trong config: `vi_vn` (mặc định) hoặc `en_us`.

## Lệnh

Mọi lệnh cần quyền cấp 2 (OP), trừ `chronicle` và `board` ai cũng dùng được. "Làng gần nhất" là làng đã ghi nhận gần bạn nhất, trong phạm vi `activeRange`.

| Lệnh | Tác dụng |
|---|---|
| `/livingvillages status` | Thông tin làng gần nhất: tên, vị trí chuông, phong cách, số dân, giường (tổng/trống), số công trình đã xây và giới hạn, cấp làng, dự án hiện tại (mẫu, tiến độ, thợ xây), thời gian chờ còn lại, bốn nhu cầu dạng thanh, tâm trạng, tác dụng của các nghề, trưởng làng cùng điều họ muốn xây và lý do |
| `/livingvillages list` | Liệt kê các làng đã ghi nhận trong thế giới (chiều không gian) hiện tại |
| `/livingvillages build` | Bắt đầu ngay công trình trưởng làng muốn (không cần gì thì xây nhà ở), bỏ qua thời gian chờ và các lần tìm chỗ thất bại (vẫn tính giới hạn công trình) |
| `/livingvillages build instant` | Như trên nhưng dựng xong cả công trình ngay lập tức (hoặc hoàn thành ngay dự án đang xây). Dùng để thử |
| `/livingvillages cancel` | Hủy dự án hiện tại. Các khối đã đặt vẫn giữ nguyên |
| `/livingvillages templates [kiểu]` | Liệt kê mẫu công trình theo nhóm, của làng gần nhất hoặc của một kiểu: `plains`, `desert`, `savanna`, `snowy`, `taiga` |
| `/livingvillages rename <tên>` | Đổi tên làng gần nhất |
| `/livingvillages chronicle` | 10 mục biên niên sử gần nhất của làng gần nhất (mọi người) |
| `/livingvillages board` | Các yêu cầu vật liệu của làng gần nhất (mọi người) |
| `/livingvillages board place` | Đặt lại bảng vật liệu (bảng chỉ tự đặt một lần) |
| `/livingvillages pause` / `resume` | Tạm dừng / tiếp tục toàn bộ mod (lưu vào mục `enabled` của config) |
| `/livingvillages speed <0.1–10>` | Hệ số tốc độ xây (lưu vào config) |
| `/livingvillages reload` | Nạp lại `config/livingvillages.json` |

## Cấu hình

File `config/livingvillages.json`. Giá trị nằm ngoài khoảng cho phép sẽ dùng mặc định và ghi cảnh báo vào log. Nếu file lỗi cú pháp, mod dùng giá trị mặc định và không ghi đè file, để bạn tự sửa. Sửa xong gõ `/livingvillages reload`.

| Khóa | Mặc định | Ý nghĩa |
|---|---|---|
| `language` | `vi_vn` | Ngôn ngữ của mọi chữ: `vi_vn` hoặc `en_us` |
| `enabled` | `true` | Công tắc chính (`pause`/`resume`) |
| `scanIntervalTicks` | `100` | Bao lâu dò chuông quanh người chơi một lần |
| `manageIntervalTicks` | `40` | Bao lâu mỗi làng cập nhật nhu cầu, cấp, trưởng làng và quyết định xây gì một lần |
| `scanRadius` | `64` | Bán kính dò chuông quanh mỗi người chơi |
| `villageRadius` | `48` | Bán kính quanh chuông để đếm dân và giường (tự nới rộng cho bao hết các nhà mod đã xây) |
| `villageMergeRadius` | `48` | Các chuông gần nhau hơn khoảng này được tính là cùng một làng |
| `activeRange` | `128` | Làng chỉ hoạt động khi có người chơi trong khoảng này |
| `freeBedThreshold` | `0` | Bắt đầu xây khi số giường trống ≤ giá trị này |
| `maxHousesPerVillage` | `10` | Số công trình mod xây tối đa cho mỗi làng. Chỉ dùng khi tắt cấp làng; bật thì theo cấp |
| `cooldownTicks` | `24000` | Thời gian chờ sau khi xây xong một nhà (24000 = một ngày) |
| `minBuildDistance` / `maxBuildDistance` | `12` / `64` | Khoảng cách từ chuông tới công trình mới (bật cấp làng thì bán kính của cấp thay cho giá trị tối đa) |
| `siteAttempts` | `48` | Số chỗ thử mỗi lần tìm vị trí |
| `margin` | `2` | Khoảng trống chừa quanh nhà |
| `maxHeightDifference` | `3` | Độ chênh cao tối đa của mặt đất ở chỗ xây |
| `templateYOffset` | `0` | Dịch nhà lên/xuống thêm. Độ cao sàn được tự tính, nên thường để 0 |
| `maxSiteFailures` | `5` | Sau số lần tìm chỗ thất bại liên tiếp này, làng ngừng xây cho tới khi có lệnh `/livingvillages build` |
| `blocksPerSecond` | `2.0` | Tốc độ xây cơ bản |
| `speedMultiplier` | `1.0` | Hệ số nhân tốc độ xây (lệnh `speed`) |
| `maxBlocksPerTickGlobal` | `4` | Số khối tối đa được đặt mỗi tick trên toàn server |
| `builderReach` | `8` | Thợ xây phải đứng gần khối tới mức này (theo chiều ngang) mới đặt được |
| `builderStuckTicks` | `1200` | Thời gian cho thợ xây đi tới công trường trước khi bị thay |
| `workStartTime` / `workEndTime` | `1000` / `11000` | Giờ làm việc (thời gian trong ngày, tính bằng tick) |
| `maxSkippedRatio` | `0.1` | Hủy dự án khi tỉ lệ khối bị chắn vượt mức này |
| `showBuilderHeldItem` | `true` | Thợ xây cầm khối đang đặt trên tay |
| `debugLogging` | `false` | Ghi thêm log về làng, chỗ xây và thợ xây |
| `allowTreeClearing` | `true` | Chặt cây tự nhiên trong chỗ xây |
| `maxTreeLogs` | `40` | Cây có nhiều khối gỗ hơn số này thì không bao giờ bị chặt |
| `maxTreesPerSite` | `4` | Số cây tối đa chặt cho một nhà |
| `replantSaplings` | `true` | Trồng lại một cây non cho mỗi cây đã chặt |
| `needsEnabled` | `true` | Nhu cầu, tâm trạng, trưởng làng (tắt = chạy như 0.1) |
| `farmersPerVillager` | `0.25` | Tỉ lệ nông dân để thức ăn đầy đủ |
| `defendersPerVillager` | `0.1` | Số golem/lính gác trên mỗi dân để an ninh đầy đủ |
| `attackPenalty` | `10` | Điểm an ninh mất cho mỗi lần quái tấn công dân gần đây |
| `attackHalfLifeTicks` | `24000` | Số lần bị tấn công giảm một nửa sau thời gian này |
| `moodEffectTicks` | `72000` | Ảnh hưởng của sự kiện lên tâm trạng nhạt dần hết sau thời gian này |
| `needThreshold` | `40` | Nhu cầu dưới mức này là chưa đủ, và trưởng làng sẽ xây để bù |
| `leaderAbsentTicks` | `12000` | Trưởng làng vắng mặt lâu hơn thế thì được thay |
| `workTimeoutTicks` | `600` | Thời gian dân làng được đi tới chỗ làm việc trước khi bỏ việc đó (cây non khi đó được trồng thẳng) |
| `levelsEnabled` | `true` | Cấp làng (cần `needsEnabled`); tắt thì giới hạn là `maxHousesPerVillage` |
| `levelRequirements` | 8 dân, 3 nghề / 20, 6, thủ thư / 35, 10, thủ thư và mục sư | Điều kiện lên Làng, Thị trấn, Thành phố |
| `levelMaxBuildings` | `[4, 10, 20, 32]` | Số công trình mod được xây ở mỗi cấp |
| `buildRadiusByLevel` | `[48, 64, 80, 96]` | Khoảng cách xa nhất tới chuông ở mỗi cấp |
| `levelCanDecrease` | `false` | Cho làng tụt cấp khi không còn đủ điều kiện |
| `workEnabled` | `true` | Việc làm của các nghề |
| `workIntervalTicks` | `600` | Bao lâu dân làng được chọn việc một lần |
| `workChance` | `0.5` | Xác suất chọn việc mỗi lần (×1,25 khi hạnh phúc, ×0,75 khi khốn khó) |
| `professionWork` | tất cả `true` | Bật/tắt từng nghề |
| `maxAnimalsPerType` | `[6, 8, 12, 16]` | Số gia súc mỗi loại tối đa làng cho sinh sản, theo cấp |
| `maxFishInBarrel` | `16` | Số cá ngư dân để trong thùng |
| `toolsmithBuildSpeedBonus` / `toolsmithBuildSpeedMax` | `0.1` / `0.3` | Tốc độ xây mỗi thợ rèn công cụ thêm vào, và tối đa |
| `cartographerRadiusBonus` | `16` | Bán kính xây thêm khi có người vẽ bản đồ |
| `smithSafetyBonus` / `smithSafetyMax` | `5` / `20` | Điểm an ninh mỗi thợ rèn giáp hoặc vũ khí thêm vào, và tối đa |
| `clericCureZombies` | `true` | Mục sư chữa dân làng zombie |
| `clericCuresPerDay` | `1` | Số lần chữa mỗi mục sư mỗi ngày |
| `clericCureRange` | `4` | Khoảng cách mục sư đứng cách dân làng zombie |
| `nameVillagers` / `nameGuards` | `true` / `true` | Đặt tên cho dân làng và lính gác |
| `showEntryTitle` | `true` | Tiêu đề khi người chơi bước vào làng |
| `greetingCooldownTicks` | `6000` | Thời gian trước khi cùng một làng chào lại cùng một người chơi |
| `voiceEnabled` | `true` | Dân làng nói chuyện |
| `voiceRange` | `24` | Dân làng trong khoảng này quanh người chơi mới nói |
| `voiceIntervalTicks` / `voiceChance` | `200` / `0.35` | Bao lâu làng được nói một lần, và xác suất (dân ngốc gấp đôi) |
| `maxBubblesPerVillage` | `2` | Số câu hiện cùng lúc mỗi làng |
| `bubbleDurationTicks` | `80` | Thời gian một câu hiện trên đầu |
| `chronicleEnabled` | `true` | Biên niên sử, sách và bia mộ (cần `needsEnabled`) |
| `announceEvents` | `true` | Báo sự kiện cho người chơi ở gần (chat hoặc thanh hành động) |
| `chronicleMaxEntries` | `100` | Số mục giữ lại mỗi làng |
| `gravesEnabled` | `true` | Bia mộ cho dân và lính gác có tên |
| `levelUpFireworks` | `true` | Bắn pháo hoa khi làng lên cấp |
| `boardEnabled` | `true` | Bảng vật liệu và giao dịch của nó (tắt thì giao dịch được gỡ khỏi trưởng làng) |
| `maxRequests` | `3` | Số vật liệu xin cùng lúc (1–3) |
| `requestExpireDays` | `3` | Yêu cầu chưa ai giao được làm mới sau số ngày này |
| `itemsPerEmerald` | gỗ 16, đá 16, kính 8, len 8, còn lại 8 | Số vật phẩm đổi một ngọc theo loại |
| `buildRoads` | `true` | Làm đường từ công trình mới |
| `roadSearchRadius` | `32` | Khoảng tìm đường làng để nối vào |
| `roadBlocks` | `dirt_path`, `smooth_sandstone` | Khối được coi là đường (thêm khối đường của mod khác vào đây) |
| `roadMaxNodes` | `4000` | Số bước tối đa khi tìm đường |
| `wallsEnabled` | `true` | Hàng rào và tường thành |
| `deferToRegrowth` | `false` | Khi `true` và có Regrowth, bỏ qua tường, hàng rào và đuốc (để Regrowth làm) |
| `palisadeMinLevel` | `1` | Cấp làng cần có hàng rào gỗ |
| `cityWallMinLevel` | `2` | Cấp làng cần có tường đá |
| `cityWallHeight` | `3` | Chiều cao tường bình thường trên mặt đất |
| `cityWallHeightMax` | `4` | Chiều cao tường khi có con tiêu (cấp Thành phố) |
| `towerSpacing` | `32` | Số cột giữa hai tháp |
| `wallMargin` | `6` | Lề nới ra quanh các nhà khi tính đa giác tường |
| `gateWidth` | `3` | Độ rộng cổng |
| `maxWallStep` | `3` | Độ chênh cao tối đa giữa hai cột tường liền nhau |
| `wallBlocksPerSecond` | `1.0` | Tốc độ mỗi thợ đá xây tường |
| `outerRingMinLevel` | `3` | Cấp làng cần có vòng tường ngoài |
| `maxRings` | `3` | Số vòng tường tối đa cùng lúc |
| `ringExpansion` | `24` | Mức nở rộng mỗi vòng tường ngoài |
| `torchesEnabled` | `true` | Tự cắm đuốc vào chỗ tối |
| `torchLightLevel` | `7` | Không cắm đuốc nếu độ sáng đã trên mức này |
| `torchSpacing` | `6` | Khoảng cách tối thiểu giữa hai đuốc mod đặt |
| `torchIntervalTicks` | `200` | Bao lâu tìm chỗ tối để cắm đuốc một lần |
| `roadCareEnabled` | `true` | Vá ổ gà, quét tuyết, nâng đường lún |
| `grassCuttingEnabled` | `true` | Cắt cỏ dại trên đường và gần cửa nhà |
| `roadCareIntervalTicks` | `200` | Bao lâu chăm sóc đường một lần |
| `careBlocksPerRun` | `4` | Số ô được sửa mỗi lần chăm sóc |
| `homeFences` | `false` | Hàng rào vườn quanh nhà mod xây |
| `homeFenceGap` | `1` | Khoảng cách từ hàng rào tới rìa nhà |

## Tương thích

- **Better Village:** hỗ trợ sẵn, không cần cài đặt gì thêm. Mẫu của Better Village tự được dùng và phân loại như trên. Better Village đặt giường vào hầu hết công trình nghề, nên có khối nghề là xưởng; giường trong xưởng, nông trại, chuồng vẫn được tính vào nhà ở sau khi xây.
- **Regrowth:** tường, hàng rào, đường và đuốc của Regrowth được coi như khối bình thường. Mod không chọn chỗ xây đè lên chúng, và khối Regrowth đặt vào công trường thì được bỏ qua, không bị ghi đè. Đường của Regrowth (đường đất, sa thạch mịn ở sa mạc) là đường làng: đường mới nối vào và không bao giờ thay chúng.
- **Guard Villagers:** lính gác không phải dân làng: không làm thợ xây hay trưởng làng, không tính vào dân số, nhưng được tính vào an ninh, có tên và có câu nói riêng. Mục sư chỉ chữa cho dân làng, vì Guard Villagers đã tự chữa cho lính gác.
- Datapack sửa bộ nhà làng cũng dùng được. Mẫu nhà rộng hơn 24×24 hoặc cao hơn 20 khối bị bỏ qua.

## Hiệu năng

- Mọi việc được chia theo chu kỳ, chỉ xử lý làng gần người chơi và không bao giờ nạp thêm chunk. Mẫu nhà được lưu tạm (cache) theo từng bộ nhà.
- Đo trên server với 3 làng xây cùng lúc: trung bình mod chỉ tốn khoảng **0,02–0,05 ms mỗi tick**.
- Thỉnh thoảng giật ngắn (vài ms, cách nhau vài giây) khi khối vừa đặt nằm trên đường đi của thợ xây. Lúc đó Minecraft tính lại đường đi, giống hệt khi người chơi đặt khối. Nhà đầu tiên sau khi bật server cũng tốn thêm chút thời gian để nạp mẫu nhà một lần.

## Giới hạn đã biết

- Làng trên đất dốc có thể không tìm được chỗ xây. Sau `maxSiteFailures` lần thất bại, làng ngừng thử cho tới khi có lệnh `/livingvillages build`.
- Chỉ hỗ trợ 5 kiểu làng vanilla.
- Đường đang làm dở, màn pháo hoa và ca chữa đang diễn ra không được lưu: khởi động lại thì đường dở bị bỏ và pháo hoa dừng.
- Biên niên sử, bia mộ, tiêu đề lên cấp và lời nói mới được test trên server không có người chơi thật; Guard Villagers không nạp được trong môi trường dev nên tên, câu nói và bia mộ của lính gác chưa được test.
- Các cột tường trên mặt nước, dung nham hoặc đất dốc quá được để là điểm yếu; tường vẫn đứng, chỉ các cột đó để hở.
- Cầu thang xoắn trong tháp được thiết kế để dân làng leo lên được; điều này mới test headless. Khả năng pathfinding thực sự phụ thuộc vào các mod khác.

## Gỡ mod

Công trình đã xây vẫn còn như khối bình thường, và dân làng giữ tên mà mod đã đặt. Câu nói đang hiện lúc gỡ mod sẽ nằm lại giữa không trung; xóa bằng `/kill @e[tag=livingvillages_bubble]`. Giao dịch của bảng vật liệu còn trên trưởng làng sẽ ở lại và thành giao dịch một lần bình thường được vanilla làm mới; muốn gỡ thì tắt `boardEnabled` và vào làng một lần trước khi gỡ mod. Dữ liệu duy nhất của mod là file `data/livingvillages.dat` trong thư mục của mỗi chiều không gian, có thể xóa đi. Gỡ mod không làm hỏng thế giới.
