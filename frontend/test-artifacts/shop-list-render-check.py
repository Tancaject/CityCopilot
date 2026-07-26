from pathlib import Path

from playwright.sync_api import sync_playwright


ARTIFACT_DIR = Path(__file__).resolve().parent / "shop-list-render"
ARTIFACT_DIR.mkdir(parents=True, exist_ok=True)


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1440, "height": 960})
        console_messages = []
        page.on("console", lambda msg: console_messages.append(f"{msg.type}: {msg.text}"))
        page.on("pageerror", lambda error: console_messages.append(f"pageerror: {error}"))
        page.add_init_script(
            """
            sessionStorage.setItem('citycopilot:selected-location', JSON.stringify({
              label: '天心区',
              address: '湖南省长沙市天心区青园街道五凌路青园小区A区',
              source: 'search',
              point: { x: 112.996, y: 28.142 },
              updatedAt: Date.now()
            }));
            """
        )
        page.route("**/api/shop/discovery**", lambda route: route.fulfill(
            status=200,
            content_type="application/json",
            body="""
            {
              "success": true,
              "data": [
                {
                  "source": "amap",
                  "sourceId": "B001",
                  "orderable": false,
                  "typeName": "餐饮服务;中餐厅;火锅店",
                  "name": "严嗲小锅菜(青园店)",
                  "area": "天心区",
                  "address": "五凌路195号",
                  "tel": "0731-12345678",
                  "businessArea": "青园",
                  "tag": "牛油锅底,小锅菜",
                  "avgPrice": 58,
                  "score": 46,
                  "distance": 15
                }
              ]
            }
            """
        ))
        page.goto("http://localhost:5182/shops")
        page.wait_for_load_state("networkidle")
        (ARTIFACT_DIR / "page.html").write_text(page.content(), encoding="utf-8")
        (ARTIFACT_DIR / "console.txt").write_text("\n".join(console_messages), encoding="utf-8")
        page.screenshot(path=str(ARTIFACT_DIR / "shop-list.png"), full_page=True)

        text = page.locator("body").inner_text()
        assert "严嗲小锅菜(青园店)" in text
        assert "外部 POI" not in text
        assert "附近地点" not in text
        assert "火锅店" in text
        assert "青园" in text
        assert "牛油锅底" in text
        assert "0731-12345678" in text
        assert page.locator(".shop-row-cover-empty").count() == 1
        browser.close()


if __name__ == "__main__":
    main()
