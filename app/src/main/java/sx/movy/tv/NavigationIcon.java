package sx.movy.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Small native outline icons; no borrowed brand assets or font glyphs. */
final class NavigationIcon extends View {
    private final String kind;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    NavigationIcon(Context context,String kind){super(context);this.kind=kind;paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);setColor(0xffccd1d7);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    void setColor(int color){paint.setColor(color);invalidate();}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);canvas.save();canvas.scale(getWidth()/24f,getHeight()/24f);
        switch(kind){
            case "home": Path home=new Path();home.moveTo(3,11);home.lineTo(12,3);home.lineTo(21,11);home.moveTo(5,10);home.lineTo(5,21);home.lineTo(10,21);home.lineTo(10,14);home.lineTo(14,14);home.lineTo(14,21);home.lineTo(19,21);home.lineTo(19,10);canvas.drawPath(home,paint);break;
            case "search":canvas.drawCircle(10,10,6,paint);canvas.drawLine(15,15,21,21,paint);break;
            case "account":canvas.drawCircle(12,7,4,paint);canvas.drawArc(4,13,20,27,180,180,false,paint);break;
            case "saved":Path bookmark=new Path();bookmark.moveTo(6,3);bookmark.lineTo(18,3);bookmark.lineTo(18,21);bookmark.lineTo(12,17);bookmark.lineTo(6,21);bookmark.close();canvas.drawPath(bookmark,paint);break;
            case "series":canvas.drawRoundRect(3,6,21,20,2,2,paint);canvas.drawLine(8,2,12,6,paint);canvas.drawLine(16,2,12,6,paint);break;
            case "movies":canvas.drawRoundRect(3,3,21,21,2,2,paint);canvas.drawLine(7,3,7,21,paint);canvas.drawLine(17,3,17,21,paint);for(int y=7;y<=17;y+=5){canvas.drawLine(3,y,7,y,paint);canvas.drawLine(17,y,21,y,paint);}break;
            case "genres":for(int x=4;x<=14;x+=10)for(int y=4;y<=14;y+=10)canvas.drawRoundRect(x,y,x+6,y+6,1,1,paint);break;
            default:if(kind.startsWith("brand:")){paint.setStyle(Paint.Style.FILL);paint.setTextSize(20);paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);paint.setTextAlign(Paint.Align.CENTER);canvas.drawText(kind.substring(6),12,19,paint);paint.setStyle(Paint.Style.STROKE);}else for(int y=6;y<=18;y+=6)canvas.drawLine(4,y,20,y,paint);break;
        }canvas.restore();
    }
}
