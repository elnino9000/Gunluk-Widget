# Günlük Widget

Android ana ekran widget'ı: büyük saat ve tarih, şu anki derece, günün en yüksek/en düşük derecesi ve sonraki 10 saatin 2'şer saatlik hava durumu. Yağmur, kar gibi olaylar büyük simgede ve uyarı yazısında görünür. Koyu tema, kırmızı/sarı vurgular.

- İnternet ve konum sadece widget'a dokununca kullanılır (pil için); diğer zamanlarda kayıtlı veriyle yeniden çizilir.
- Konum etiketi mahalle adını gösterir (bulunamazsa ilçe).
- Saate dokununca saat/alarm, tarihe dokununca takvim açılır.
- Hava verisi: Open-Meteo (ücretsiz, anahtar gerekmez). Model tahminidir, ölçüm değildir.
- APK her `push`ta GitHub Actions ile derlenir; **Releases > son-surum** sayfasından indirilir.
