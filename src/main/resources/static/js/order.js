/* Xử lý trên trang Checkout và Order by link:
   - nút Apply voucher
   - Checkout: tính lại phí ship / tổng tiền khi đổi Pickup <-> Delivery
   - Order by link: 1 ô nhập link -> hiện ảnh, tên, ISBN, giá bìa và giá của khách theo loại bìa;
     khách bấm OK -> thêm vào danh sách, xóa trống ô link; bảng thanh toán hiện giá bìa + giá của khách.
     Giá do máy chủ tính sẵn (đã làm tròn); CK toàn bộ free ship, COD cọc trước */
(function () {
    'use strict';

    var base = (document.body.getAttribute('data-base') || '/').replace(/\/?$/, '/');
    var summary = document.getElementById('summary');
    var mode = summary ? summary.getAttribute('data-mode') : null;
    var voucherPercent = 0;

    function vnd(n) {
        return Math.round(n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, '.') + '₫';
    }
    function checked(name) {
        var el = document.querySelector('input[name="' + name + '"]:checked');
        return el ? el.value : null;
    }
    function setText(id, text) {
        var el = document.getElementById(id);
        if (el) el.textContent = text;
    }
    function show(id, visible) {
        var el = document.getElementById(id);
        if (el) el.style.display = visible ? '' : 'none';
    }

    // ================= CHECKOUT (giỏ hàng) =================
    function recalcCheckout() {
        var subtotal = +summary.getAttribute('data-subtotal');
        var shipFee = +summary.getAttribute('data-ship-fee');
        var freeFrom = +summary.getAttribute('data-free-from');
        var discount = Math.floor(subtotal * voucherPercent / 100);
        var after = subtotal - discount;
        var ship = checked('receivingMethod') === 'PICKUP' ? 0 : (after >= freeFrom ? 0 : shipFee);
        show('discountRow', discount > 0);
        setText('discountVal', '-' + vnd(discount));
        setText('shipVal', ship === 0 ? 'Free' : vnd(ship));
        setText('totalVal', vnd(after + ship));
    }

    // ================= ORDER BY LINK =================
    // Một ô nhập link -> đọc thông tin (ảnh, tên, ISBN, giá theo loại bìa) -> khách bấm OK -> thêm vào danh sách bên dưới
    var linkInput = document.getElementById('linkInput');
    var entryCard = document.getElementById('entryCard');
    var entryStatus = document.getElementById('entryStatus');
    var bookRows = document.getElementById('bookRows');
    var rowTpl = document.getElementById('bookRowTpl');
    var listEmpty = document.getElementById('listEmpty');
    var linkSummary = document.getElementById('linkSummary');
    var MAX_ROWS = 20;
    var codShip = summary ? +summary.getAttribute('data-cod-ship') || 0 : 0;
    var roundTo = summary ? +summary.getAttribute('data-round') || 1 : 1;
    function roundUp(v) { return roundTo <= 1 ? Math.ceil(v) : Math.ceil(v / roundTo - 1e-9) * roundTo; }
    function roundDown(v) { return roundTo <= 1 ? Math.floor(v) : Math.floor(v / roundTo + 1e-9) * roundTo; }

    var COVER_LABEL = { PAPERBACK: 'Paperback', HARDCOVER: 'Hardcover', LARGE_PRINT: 'Large Print' };
    var FORMAT_LABEL = { PAPERBACK: 'Paperback', HARDCOVER: 'Hardcover', LARGE_PRINT: 'Large Print', EBOOK: 'eBook', AUDIO: 'Audiobook' };
    var previewCache = {};   // url -> dữ liệu đọc được (tránh đọc lại cùng một link)

    function validUrl(url) { return /^https?:\/\/\S+$/.test(url); }
    function hostOf(url) {
        try { return new URL(url).hostname.replace(/^www\./, ''); } catch (e) { return ''; }
    }
    /** Giá của 1 loại bìa trong dữ liệu đọc được: {coverPrice, unitPrice, depositUnit} hoặc null */
    function priceFor(data, cover) {
        var c = data && data.covers ? data.covers[cover] : null;
        return c && c.status === 'ok' ? c : null;
    }
    function statusFor(data, cover) {
        var c = data && data.covers ? data.covers[cover] : null;
        return c ? c.status : null;
    }
    function fetchPreview(url) {
        if (previewCache[url]) return previewCache[url];
        previewCache[url] = fetch(base + 'api/link-preview?url=' + encodeURIComponent(url))
            .then(function (r) { return r.json(); })
            .catch(function () { delete previewCache[url]; return null; });
        return previewCache[url];
    }
    function thumb(container, img) {
        container.innerHTML = '';
        if (img) {
            var im = document.createElement('img');
            im.src = img; im.alt = ''; im.referrerPolicy = 'no-referrer';
            im.onerror = function () { this.remove(); container.textContent = '📖'; };
            container.appendChild(im);
        } else {
            container.textContent = '📖';
        }
    }

    // ---------- Ô nhập link + thẻ xem trước ----------
    var entry = { url: null, data: null, loading: false, touched: false };

    function entryCover() {
        var r = entryCard.querySelector('input[name="entryCover"]:checked');
        return r ? r.value : 'PAPERBACK';
    }
    function setEntryStatus(text, cls) {
        entryStatus.textContent = text || '';
        entryStatus.className = 'lp-status' + (cls ? ' ' + cls : '');
    }
    function resetEntry(clearInput) {
        entry = { url: null, data: null, loading: false, touched: false };
        entryCard.hidden = true;
        if (clearInput) linkInput.value = '';
    }

    /** Hiện tên, ảnh, ISBN, loại bìa, giá bìa và giá của khách cho link đang nhập */
    function renderEntry() {
        var data = entry.data;
        var cover = entryCover();
        entryCard.hidden = false;
        thumb(entryCard.querySelector('.lp-thumb'), data && data.image);
        setText('entryTitle', (data && data.title) || hostOf(entry.url) || entry.url);
        setText('entryHost', (data && data.host) || hostOf(entry.url));
        setText('entryIsbn', (data && data.isbn) || 'Not found');
        setText('entryFormat', data && data.pageFormat ? (FORMAT_LABEL[data.pageFormat] || data.pageFormat) : 'Not detected');

        // Khóa loại bìa sách không bán trên trang đó
        entryCard.querySelectorAll('input[name="entryCover"]').forEach(function (r) {
            var label = r.closest('label');
            var na = statusFor(data, r.value) === 'unavailable';
            r.disabled = na;
            label.classList.toggle('na', na);
            var span = label.querySelector('span');
            if (!span.getAttribute('data-text')) span.setAttribute('data-text', span.textContent);
            span.textContent = span.getAttribute('data-text') + (na ? ' · Not available' : '');
        });

        var price = priceFor(data, cover);
        var anyPrice = !!(data && data.covers && Object.keys(data.covers).some(function (k) { return priceFor(data, k); }));
        setText('entryCoverPrice', price && price.coverPrice ? vnd(price.coverPrice) : '—');
        setText('entryYourPrice', price ? vnd(price.unitPrice) : 'To be confirmed');
        var warn = '';
        if (!data) warn = "We couldn't read this link. You can still add it – we'll confirm the price before you pay.";
        else if (!price && anyPrice) warn = "We couldn't find the " + COVER_LABEL[cover] + " price on this page. You can still add it – we'll confirm it before you pay.";
        else if (!price) warn = data.message || "We couldn't read the price automatically. You can still add it – we'll confirm the price before you pay.";
        setText('entryWarn', warn);
        show('entryWarn', !!warn);
        var cur = entryCard.querySelector('input[name="entryCover"]:checked');
        document.getElementById('entryOk').disabled = !!(cur && cur.disabled);
    }

    function loadEntry() {
        var url = linkInput.value.trim();
        if (!url) { resetEntry(false); setEntryStatus(''); return; }
        if (!validUrl(url)) {
            resetEntry(false);
            setEntryStatus('Please paste a full link starting with http:// or https://', 'warn');
            return;
        }
        if (entry.url === url && (entry.loading || entry.data !== undefined)) return;
        entry = { url: url, data: undefined, loading: true, touched: false };
        entryCard.hidden = true;
        setEntryStatus('Reading book details… some websites take up to 30 seconds.', 'loading');
        fetchPreview(url).then(function (data) {
            if (entry.url !== url) return;               // khách đã đổi link khác
            entry.loading = false;
            entry.data = data && (data.ok || data.covers) ? data : null;
            // Chọn sẵn loại bìa của trang đang mở
            var sug = entry.data && entry.data.suggestedCover;
            var radios = entryCard.querySelectorAll('input[name="entryCover"]');
            radios.forEach(function (r) { r.checked = r.value === (sug || 'PAPERBACK'); });
            setEntryStatus('');
            renderEntry();
            var checkedRadio = entryCard.querySelector('input[name="entryCover"]:checked');
            if (checkedRadio && checkedRadio.disabled) {
                var firstOk = Array.prototype.find.call(radios, function (r) { return !r.disabled; });
                if (firstOk) { firstOk.checked = true; renderEntry(); }
            }
        });
    }

    function confirmEntry() {
        if (!entry.url || entry.loading) return;
        var cover = entryCover();
        var rows = bookRows.querySelectorAll('.bl-row');
        // Cùng link + cùng loại bìa đã có trong danh sách -> tăng số lượng
        var same = Array.prototype.find.call(rows, function (r) {
            return r.getAttribute('data-url') === entry.url && hidden(r, 'coverType').value === cover;
        });
        if (same) {
            var q = same.querySelector('.qty-val');
            q.value = Math.min(99, (parseInt(q.value, 10) || 1) + 1);
        } else {
            if (rows.length >= MAX_ROWS) { setEntryStatus('You can order up to ' + MAX_ROWS + ' books per order.', 'warn'); return; }
            var row = rowTpl.content.firstElementChild.cloneNode(true);
            bookRows.appendChild(row);
            fillRow(row, entry.url, entry.data, cover, (entry.data && entry.data.title) || '');
        }
        var title = (entry.data && entry.data.title) || hostOf(entry.url);
        resetEntry(true);
        setEntryStatus('Added “' + title + '” to your list.', 'ok');
        reindex();
        linkInput.focus();
    }

    function hidden(row, suffix) { return row.querySelector('input[name$=".' + suffix + '"]'); }

    /** Ghi dữ liệu vào 1 dòng danh sách */
    function fillRow(row, url, data, cover, title) {
        row.setAttribute('data-url', url);
        row._data = data;
        hidden(row, 'url').value = url;
        hidden(row, 'title').value = title || '';
        hidden(row, 'coverType').value = cover;
        paintRow(row);
    }
    function paintRow(row) {
        var data = row._data;
        var url = row.getAttribute('data-url');
        var cover = hidden(row, 'coverType').value || 'PAPERBACK';
        thumb(row.querySelector('.sum-thumb'), data && data.image);
        row.querySelector('.t').textContent = hidden(row, 'title').value || (data && data.title) || hostOf(url) || url;
        var meta = [COVER_LABEL[cover] || cover];
        if (data && data.isbn) meta.push('ISBN ' + data.isbn);
        meta.push(hostOf(url));
        row.querySelector('.m').textContent = meta.join(' · ');
    }

    // ---------- Danh sách ----------
    function reindex() {
        var rows = bookRows.querySelectorAll('.bl-row');
        rows.forEach(function (row, i) {
            row.querySelector('.bl-num').textContent = i + 1;
            row.querySelectorAll('input[name]').forEach(function (el) {
                el.name = el.name.replace(/items\[\d+\]/, 'items[' + i + ']');
            });
        });
        listEmpty.style.display = rows.length ? 'none' : '';
        setText('listCount', rows.length ? '(' + rows.length + ')' : '');
        recalc();
    }

    function recalcLink() {
        var coverTotal = 0, subtotal = 0, depositRaw = 0, missing = false, count = 0;
        linkSummary.innerHTML = '';

        bookRows.querySelectorAll('.bl-row').forEach(function (row) {
            var qtyEl = row.querySelector('.qty-val');
            var qty = Math.min(99, Math.max(1, parseInt(qtyEl.value, 10) || 1));
            var cover = hidden(row, 'coverType').value;
            var price = priceFor(row._data, cover);
            var unit = price ? +price.unitPrice || 0 : 0;        // giá của khách 1 cuốn (máy chủ tính, đã làm tròn)
            var coverUnit = price ? +price.coverPrice || 0 : 0;  // giá bìa 1 cuốn (VND)
            count += qty;
            if (unit > 0) {
                subtotal += unit * qty;
                coverTotal += (coverUnit || unit) * qty;
                depositRaw += (+price.depositUnit || 0) * qty;
            } else {
                missing = true;
            }

            var item = document.createElement('div');
            item.className = 'sum-item';
            item.innerHTML = '<div class="sum-thumb"></div><div class="sum-info"><span class="t"></span><span class="q"></span></div>' +
                '<span class="sum-price"><s class="sp-cover"></s><span class="sp-your"></span></span>';
            thumb(item.querySelector('.sum-thumb'), row._data && row._data.image);
            item.querySelector('.t').textContent = row.querySelector('.bl-book .t').textContent;
            item.querySelector('.q').textContent = 'Qty ' + qty + ' · ' + (COVER_LABEL[cover] || '');
            var your = item.querySelector('.sp-your');
            var strike = item.querySelector('.sp-cover');
            if (unit > 0) {
                your.textContent = vnd(unit * qty);
                strike.textContent = coverUnit > unit ? vnd(coverUnit * qty) : '';
            } else {
                your.textContent = row._loading ? 'Reading…' : 'To be confirmed';
                item.querySelector('.sum-price').classList.add('pending');
            }
            linkSummary.appendChild(item);
        });
        if (!linkSummary.children.length) {
            linkSummary.innerHTML = '<div class="sum-empty">Books you add will appear here.</div>';
        }

        var option = checked('paymentOption') || 'BANK_FULL';
        var pickup = checked('receivingMethod') === 'PICKUP';
        var discount = roundDown(subtotal * voucherPercent / 100);
        var after = subtotal - discount;
        var ship, total, payNow, cod;
        if (option === 'COD_DEPOSIT') {
            ship = pickup ? 0 : codShip;
            total = after + ship;
            payNow = Math.min(roundUp(depositRaw), total);
            cod = total - payNow;
        } else {
            ship = 0; total = after; payNow = after; cod = 0;
        }

        var ready = count > 0 && !missing;
        setText('coverVal', coverTotal > 0 ? vnd(coverTotal) : '—');
        setText('subVal', subtotal > 0 ? vnd(subtotal) : '—');
        show('discountRow', discount > 0);
        setText('discountVal', '-' + vnd(discount));
        setText('shipVal', ship === 0 ? 'Free' : vnd(ship));
        setText('totalVal', ready ? vnd(total) : '—');
        setText('payNowLabel', option === 'COD_DEPOSIT' ? 'Deposit now by transfer' : 'Pay now by transfer');
        setText('payNowVal', ready ? vnd(payNow) : '—');
        show('codRow', option === 'COD_DEPOSIT');
        setText('codVal', ready ? vnd(cod) : '—');
        show('missingPrice', count > 0 && missing);
    }

    function recalc() {
        if (mode === 'checkout') recalcCheckout();
        else if (mode === 'link' && bookRows) recalcLink();
    }

    if (linkInput && bookRows && rowTpl) {
        var timer;
        linkInput.addEventListener('input', function () {
            clearTimeout(timer);
            timer = setTimeout(loadEntry, 600);
        });
        linkInput.addEventListener('paste', function () { setTimeout(loadEntry, 50); });
        linkInput.addEventListener('keydown', function (e) {
            if (e.key !== 'Enter') return;
            e.preventDefault();                                 // Enter không gửi form
            if (entry.url && !entry.loading && entry.url === linkInput.value.trim()) confirmEntry();
            else loadEntry();
        });
        entryCard.addEventListener('change', function (e) {
            if (e.target.name === 'entryCover') renderEntry();
        });
        document.getElementById('entryOk').addEventListener('click', confirmEntry);
        document.getElementById('entryCancel').addEventListener('click', function () {
            resetEntry(true);
            setEntryStatus('');
            linkInput.focus();
        });

        bookRows.addEventListener('click', function (e) {
            var row = e.target.closest('.bl-row');
            if (!row) return;
            var q = row.querySelector('.qty-val');
            if (e.target.classList.contains('plus')) q.value = Math.min(99, (parseInt(q.value, 10) || 1) + 1);
            else if (e.target.classList.contains('minus')) q.value = Math.max(1, (parseInt(q.value, 10) || 1) - 1);
            else if (e.target.classList.contains('bl-remove')) { row.remove(); reindex(); return; }
            else return;
            recalc();
        });
        bookRows.addEventListener('input', recalc);

        // Gửi đơn khi ô link còn link chưa bấm OK -> nhắc khách
        document.getElementById('orderForm').addEventListener('submit', function (e) {
            if (linkInput.value.trim()) {
                e.preventDefault();
                setEntryStatus('Press OK to add this book to your list (or Cancel to clear the link) before placing the order.', 'warn');
                linkInput.scrollIntoView({ behavior: 'smooth', block: 'center' });
            }
        });

        // Danh sách hiện lại khi form có lỗi: đọc lại ảnh + giá cho từng dòng
        bookRows.querySelectorAll('.bl-row').forEach(function (row) {
            var url = row.getAttribute('data-url');
            row._loading = true;
            fetchPreview(url).then(function (data) {
                row._loading = false;
                row._data = data && (data.ok || data.covers) ? data : null;
                paintRow(row);
                recalc();
            });
        });
        if (linkInput.value.trim()) loadEntry();   // link điền sẵn từ đường dẫn
        reindex();
    }

    document.querySelectorAll('input[name="receivingMethod"], input[name="paymentOption"]').forEach(function (r) {
        r.addEventListener('change', recalc);
    });

    // ================= VOUCHER =================
    var applyBtn = document.getElementById('applyVoucher');
    var voucherInput = document.getElementById('voucherCode');
    var voucherMsg = document.getElementById('voucherMsg');

    function applyVoucher() {
        var code = voucherInput.value.trim();
        voucherMsg.className = 'voucher-msg';
        if (!code) {
            voucherPercent = 0;
            voucherMsg.textContent = '';
            recalc();
            return;
        }
        fetch(base + 'api/voucher?code=' + encodeURIComponent(code))
            .then(function (r) { return r.json(); })
            .then(function (res) {
                voucherPercent = res.valid ? res.percent : 0;
                voucherMsg.classList.add(res.valid ? 'ok' : 'bad');
                voucherMsg.textContent = res.message;
                recalc();
            })
            .catch(function () {
                voucherMsg.classList.add('bad');
                voucherMsg.textContent = 'Could not check the code, please try again';
            });
    }

    if (applyBtn && voucherInput) {
        applyBtn.addEventListener('click', applyVoucher);
        voucherInput.addEventListener('keydown', function (e) {
            if (e.key === 'Enter') { e.preventDefault(); applyVoucher(); }
        });
        if (voucherInput.value.trim() && !voucherMsg.querySelector('.err')) applyVoucher();
    }

    recalc();
})();
