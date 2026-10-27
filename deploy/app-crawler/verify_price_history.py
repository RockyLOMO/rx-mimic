"""Local post-deployment check: daily history reads and cached refresh must cause no product visits."""
import argparse
import datetime as dt
import json
import time
from pathlib import Path
from verify_products import Api, VerificationFailed


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--access-file',type=Path,required=True)
    parser.add_argument('--refresh-watch-id',type=int,
                        help='Allow one initial real refresh, then verify its Bloom-filter hit without more visits')
    args=parser.parse_args()
    api=Api(json.loads(args.access_file.read_text(encoding='utf-8-sig')))
    if args.refresh_watch_id:
        initial=api.call('/price-watches/'+str(args.refresh_watch_id)+'/refresh',{})
        if initial.get('taskId'):
            deadline=time.monotonic()+180
            while time.monotonic()<deadline:
                state=api.call('/tasks/'+str(initial['taskId']))['status']
                if state=='SUCCEEDED': break
                if state in ('FAILED','TIMED_OUT','CANCELLED','WAITING_USER'):
                    raise VerificationFailed('INITIAL_REFRESH_NOT_SUCCESSFUL')
                time.sleep(3)
            else: raise VerificationFailed('INITIAL_REFRESH_DEADLINE')
    before=api.call('/price-watches/budget')
    watches=api.call('/price-watches')
    if not watches: raise VerificationFailed('NO_WATCHES')
    # AnZhi uses Asia/Shanghai (UTC+8); keep this Windows acceptance script dependency-free.
    today=dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).date()
    cached=0
    for w in watches:
        prefix='/products/'+w['platform']+'/'+w['productId']+'/snapshots'
        default=api.call(prefix)
        for days in (30,60,90):
            rows=api.call(prefix+'?days='+str(days))
            dates=[r['date'] for r in rows]
            if dates!=sorted(set(dates)): raise VerificationFailed('DAILY_DUPLICATE_OR_ORDER')
            if any(not today-dt.timedelta(days=days-1)<=dt.date.fromisoformat(d)<=today for d in dates):
                raise VerificationFailed('HISTORY_RANGE')
            if any(r['id']<=0 or r['fetchStatus']!=1 or r['priceAmount']<=0 for r in rows):
                raise VerificationFailed('HISTORY_NOT_REAL_SUCCESS')
            if days==30 and rows!=default: raise VerificationFailed('DEFAULT_NOT_30')
    if args.refresh_watch_id:
        result=api.call('/price-watches/'+str(args.refresh_watch_id)+'/refresh',{})
        if result.get('cached') is not True or result.get('taskId') is not None:
            raise VerificationFailed('REPEATED_DAILY_REFRESH_QUEUED')
        cached=1
    after=api.call('/price-watches/budget')
    if before['platforms']!=after['platforms']: raise VerificationFailed('PRODUCT_VISITS_CHANGED')
    print(f'DAILY_HISTORY_VERIFY_OK watches={len(watches)} ranges=30,60,90 cachedRefresh={cached} productVisitDelta=0')


if __name__=='__main__':
    try: main()
    except VerificationFailed as e:
        print('DAILY_HISTORY_VERIFY_FAILED code='+str(e));raise SystemExit(1)
    except Exception:
        print('DAILY_HISTORY_VERIFY_FAILED code=PROTOCOL_OR_CONFIG_ERROR');raise SystemExit(1)
