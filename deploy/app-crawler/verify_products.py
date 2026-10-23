"""Bounded live acceptance through the authenticated API. Never log raw materials."""
import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path


class VerificationFailed(RuntimeError):
    pass


class Api:
    def __init__(self, config):
        self.base = config['baseUrl'].rstrip('/')
        if not self.base.startswith('https://'):
            raise VerificationFailed('HTTPS_API_REQUIRED')
        self.key = config['apiKey']

    def call(self, path, payload=None, idempotency=None):
        headers = {'Authorization': 'Bearer ' + self.key}
        if payload is not None:
            headers['Content-Type'] = 'application/json'
        if idempotency:
            headers['Idempotency-Key'] = idempotency
        request = urllib.request.Request(self.base + path,
            data=None if payload is None else json.dumps(payload).encode(), headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=150) as response:
                body = json.load(response)
        except urllib.error.HTTPError as error:
            # URLs and response bodies may contain materials/credentials: emit neither.
            raise VerificationFailed('HTTP_' + str(error.code)) from None
        except Exception:
            raise VerificationFailed('API_UNREACHABLE') from None
        if body.get('success') is not True:
            raise VerificationFailed('API_REJECTED')
        return body.get('data')

    def task(self, action, request):
        submitted = self.call('/tasks', {'action': action, 'request': request}, str(uuid.uuid4()))
        task_id = str(submitted['taskId'])
        if not task_id.isdigit():
            raise VerificationFailed('INVALID_TASK_ID')
        finished = False
        try:
            deadline = time.monotonic() + 660
            while time.monotonic() < deadline:
                status = self.call('/tasks/' + task_id)
                state = status['status']
                if state == 'SUCCEEDED':
                    finished = True
                    result = self.call('/tasks/' + task_id + '/result')
                    if result.get('status') != 'SUCCESS':
                        business = result.get('status', '')
                        code = business if re.fullmatch(r'[A-Z_]{1,64}', business or '') else 'NOT_SUCCESS'
                        raise VerificationFailed(action + '_BUSINESS_' + code)
                    return result
                if state == 'WAITING_USER':
                    raise VerificationFailed('LOGIN_OR_MANUAL_ACTION_REQUIRED')
                if state in ('FAILED', 'TIMED_OUT', 'CANCELLED'):
                    finished = True
                    code = status.get('errorCode', '')
                    try:
                        result = self.call('/tasks/' + task_id + '/result')
                        code = result.get('failureCode') or code
                    except VerificationFailed:
                        pass
                    suffix = '_' + code if re.fullmatch(r'[A-Z_]{1,64}', code or '') else ''
                    raise VerificationFailed(action + '_TASK_' + state + suffix)
                time.sleep(3)
            raise VerificationFailed('TASK_DEADLINE')
        finally:
            if not finished:
                try:
                    self.call('/tasks/' + task_id + '/cancel', {})
                except VerificationFailed:
                    pass


def verify_platform(api, platform):
    search = platform + 'UnionSearchProducts'
    products = api.task(search, {'keyword': '抽纸', 'maxResults': 2}).get('items', [])
    if not products:
        raise VerificationFailed('NO_PRODUCTS')
    sample = products[0]
    material = sample.get('encryptedProductId') or sample.get('productId')
    if not material:
        raise VerificationFailed('NO_PRODUCT_IDENTITY')
    promotion = api.task('getPromotionUrl' if platform == 'jd' else 'getTbPromotionUrl',
                         {'keyword': material, 'adSiteName': '0'})
    product_id = promotion.get('productId', '')
    if promotion.get('identityResolved') is not True or not re.fullmatch(r'[0-9]+', product_id):
        raise VerificationFailed('NUMERIC_ID_UNRESOLVED')
    # Re-submit the same material: exercises the encrypted-ID cache and numeric queue boundary.
    numeric = api.task(search, {'keyword': material, 'maxResults': 2})
    candidates = numeric.get('items', [])
    if not candidates:
        raise VerificationFailed('CACHED_ID_SEARCH_EMPTY')
    explicit = api.task(search, {'keyword': product_id, 'maxResults': 2})
    if not explicit.get('items'):
        raise VerificationFailed('NUMERIC_SEARCH_EMPTY')
    # Search cards can still be encrypted. Confirm an exact numeric match through promotion.
    repeated = api.task('getPromotionUrl' if platform == 'jd' else 'getTbPromotionUrl',
                        {'keyword': product_id, 'adSiteName': '0'})
    if repeated.get('productId') != product_id or repeated.get('identityResolved') is not True:
        raise VerificationFailed('NUMERIC_SEARCH_MISMATCH')
    watches = api.call('/price-watches')
    watch = next((row for row in watches if row.get('platform') == platform
                  and row.get('productId') == product_id), None)
    if watch is None:
        raise VerificationFailed('WATCH_NOT_INGESTED')
    if any(field in watch for field in ('encryptedProductId', 'unionProductId', 'promotionUrl')):
        raise VerificationFailed('WATCH_MATERIAL_LEAK')
    snapshots = api.call('/price-watches/' + str(watch['id']) + '/snapshots')
    if not snapshots:
        raise VerificationFailed('PRICE_NOT_INGESTED')
    raw_price = str(promotion.get('productInfo', {}).get('price', ''))
    price = raw_price if re.fullmatch(r'[0-9]+(?:\.[0-9]+)?', raw_price) else 'recorded'
    print(f'PRODUCT_VERIFY_OK platform={platform} productId={product_id} price={price}', flush=True)


def verify_tb_analysis(api, product_id):
    """Exact cold lookup, redirect check, numeric/long-link/short-link search, then persisted price."""
    if not re.fullmatch(r'[0-9]{5,20}', product_id):
        raise VerificationFailed('INVALID_NUMERIC_ID')
    promotion = api.task('getTbPromotionUrl', {'keyword': product_id, 'adSiteName': '0'})
    if promotion.get('identityResolved') is not True or promotion.get('productId') != product_id:
        raise VerificationFailed('ANALYSIS_IDENTITY_MISMATCH')
    price = str(promotion.get('productInfo', {}).get('price', ''))
    if not re.fullmatch(r'[0-9]+(?:\.[0-9]+)?', price):
        raise VerificationFailed('ANALYSIS_PRICE_MISSING')
    link = promotion.get('promotionUrl', '')
    if not link.startswith('https://'):
        raise VerificationFailed('ANALYSIS_PROMOTION_LINK_MISSING')
    for label, value in [('numeric', product_id),
                         ('long-link', 'https://item.taobao.com/item.htm?id=' + product_id),
                         ('promotion-link', link)]:
        result = api.task('tbUnionSearchProducts', {'keyword': value, 'maxResults': 2})
        items = result.get('items', [])
        if len(items) != 1 or items[0].get('productId') != product_id or items[0].get('identityResolved') is not True:
            raise VerificationFailed('ANALYSIS_' + label.upper().replace('-', '_') + '_MISMATCH')
        print(f'TB_ANALYSIS_INPUT_OK type={label} productId={product_id}', flush=True)
    watches = api.call('/price-watches')
    watch = next((row for row in watches if row.get('platform') == 'tb' and row.get('productId') == product_id), None)
    if watch is None:
        raise VerificationFailed('WATCH_NOT_INGESTED')
    snapshots = api.call('/price-watches/' + str(watch['id']) + '/snapshots')
    if not snapshots:
        raise VerificationFailed('PRICE_NOT_INGESTED')
    print(f'TB_ANALYSIS_VERIFY_OK productId={product_id} price={price} snapshots={len(snapshots)}', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--access-file', type=Path, required=True,
                        help='Local credential JSON containing baseUrl and apiKey; never uploaded')
    parser.add_argument('--platform', choices=('jd', 'tb'), help='Verify only one platform')
    parser.add_argument('--tb-product-id', help='Verify exact Taobao analysis lookup with a numeric product ID')
    args = parser.parse_args()
    config = json.loads(args.access_file.read_text(encoding='utf-8-sig'))
    api = Api(config)
    api.call('/price-watches/budget')
    if args.tb_product_id:
        verify_tb_analysis(api, args.tb_product_id)
        return 0
    failed = False
    for platform in ((args.platform,) if args.platform else ('jd', 'tb')):
        try:
            verify_platform(api, platform)
        except VerificationFailed as error:
            failed = True
            print(f'PRODUCT_VERIFY_FAILED platform={platform} code={error}', flush=True)
    return 1 if failed else 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception:
        print('PRODUCT_VERIFY_FAILED code=CONFIG_OR_PROTOCOL_ERROR', flush=True)
        sys.exit(1)
