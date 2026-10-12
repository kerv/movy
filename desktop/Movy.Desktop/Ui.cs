using System.IO;
using System.Net.Http;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;

namespace Movy.Desktop;
internal static class Ui
{
    public static Brush Gold => new SolidColorBrush(Color.FromRgb(233,174,54));
    public static Brush Muted => new SolidColorBrush(Color.FromRgb(170,176,182));
    public static StackPanel Column() => new();
    public static WrapPanel Row() => new() { Margin = new Thickness(0,8,0,8) };
    public static TextBlock Text(string value, double size=15, Brush? color=null) => new() { Text=value, FontSize=size, Foreground=color??Brushes.White, TextWrapping=TextWrapping.Wrap, Margin=new Thickness(0,0,0,10) };
    public static TextBlock Heading(string value, double size=24) { var text=Text(value,size); text.FontWeight=FontWeights.SemiBold; text.Margin=new Thickness(0,20,0,14); return text; }
    public static Button Button(string label, Action click) { var button=new Button { Content=label }; button.Click+=(_,_)=>click(); return button; }
    public static Button AsyncButton(string label, Func<Task> click) => Button(label, async()=>{try{await click();}catch(OperationCanceledException){}catch{MessageBox.Show("That action could not complete. Check your connection and try again.","Movy");}});
    public static void Fade(FrameworkElement view) => view.BeginAnimation(UIElement.OpacityProperty,new DoubleAnimation(0,1,TimeSpan.FromMilliseconds(240)) { EasingFunction=new QuadraticEase { EasingMode=EasingMode.EaseOut } });
    private static readonly HttpClient Images = new() { Timeout=TimeSpan.FromSeconds(15) };
    private static readonly SemaphoreSlim ImageGate = new(6);
    public static Image Artwork(string url, double width, double height, bool backdrop=false)
    {
        var image=new Image { Width=width, Height=height, Stretch=Stretch.UniformToFill, HorizontalAlignment=HorizontalAlignment.Left };
        if(!Movy.Core.Security.Https(url))return image;
        bool loading=false; image.Loaded+=async(_,_)=>{
            if(loading||image.Source!=null)return;loading=true;
            try { await ImageGate.WaitAsync(); try { using var response=await Images.GetAsync(Movy.Core.Security.Image(url),HttpCompletionOption.ResponseHeadersRead);response.EnsureSuccessStatusCode();
                await using var input=await response.Content.ReadAsStreamAsync();using var bytes=new MemoryStream();byte[] buffer=new byte[65536];int read;while((read=await input.ReadAsync(buffer))>0){if(bytes.Length+read>8*1024*1024)throw new InvalidDataException();bytes.Write(buffer,0,read);}bytes.Position=0;
                var bitmap=new BitmapImage();bitmap.BeginInit();bitmap.CacheOption=BitmapCacheOption.OnLoad;bitmap.DecodePixelWidth=backdrop?1280:300;bitmap.StreamSource=bytes;bitmap.EndInit();bitmap.Freeze();image.Source=bitmap;Fade(image);
            } finally { ImageGate.Release(); } } catch { /* Artwork is optional; names remain visible. */ } finally { loading=false; }
        };return image;
    }
    public static string Time(double seconds) => double.IsFinite(seconds) && seconds>=0 ? seconds>=3600 ? TimeSpan.FromSeconds(seconds).ToString(@"h\:mm\:ss") : TimeSpan.FromSeconds(seconds).ToString(@"m\:ss") : "0:00";
    public static Window Dialog(Window owner,string title, UIElement body,double width=480) => new() { Owner=owner, Title=title, Width=width, SizeToContent=SizeToContent.Height, MaxHeight=760, WindowStartupLocation=WindowStartupLocation.CenterOwner, Content=new ScrollViewer { Content=new Border { Padding=new Thickness(24), Child=body }, VerticalScrollBarVisibility=ScrollBarVisibility.Auto }, ResizeMode=ResizeMode.NoResize, ShowInTaskbar=false };
    public static string? Prompt(Window owner,string title,string label,bool secret=false)
    {
        var body=Column();body.Children.Add(Text(label));TextBox? input=secret?null:new TextBox();PasswordBox? password=secret?new PasswordBox():null;body.Children.Add((UIElement?)input??password!);
        var dialog=Dialog(owner,title,body);string? result=null;var row=Row();row.Children.Add(Button("Continue",()=>{result=input?.Text??password!.Password;dialog.Close();}));row.Children.Add(Button("Cancel",()=>dialog.Close()));body.Children.Add(row);dialog.Loaded+=(_,_)=>((UIElement?)input??password!).Focus();dialog.ShowDialog();return result;
    }
    public static int Choose(Window owner,string title,IEnumerable<string> labels)
    {
        var body=Column();var dialog=Dialog(owner,title,body);int selected=-1,index=0;
        foreach(string label in labels){int item=index++;var button=Button(label,()=>{selected=item;dialog.Close();});button.HorizontalContentAlignment=HorizontalAlignment.Left;body.Children.Add(button);}body.Children.Add(Button("Cancel",()=>dialog.Close()));dialog.ShowDialog();return selected;
    }
}
